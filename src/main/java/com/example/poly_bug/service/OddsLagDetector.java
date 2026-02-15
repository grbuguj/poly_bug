package com.example.poly_bug.service;

import com.example.poly_bug.config.CoinConfig;
import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.service.BinanceWebSocketService.PriceSpike;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * ⚡ 오즈 지연 감지기 (Odds Lag Detector) — 속도 최적화판
 *
 * 속도 파이프라인:
 *   WebSocket 틱 (~100ms) → 스파이크 감지 (0ms)
 *   → 비동기 핸들러 전환 (0ms) → 캐시 오즈 조회 (0ms, 사전 폴링)
 *   → 갭 계산 (0ms) → 배팅 = 총 ~100ms
 *
 * 핵심 최적화:
 *   1. 오즈 백그라운드 폴링 (3초마다) → 스파이크 시 HTTP 콜 없음
 *   2. 비동기 핸들러 → WebSocket 스레드 블로킹 없음
 *   3. 스파이크 디바운스 → 같은 방향 중복 트리거 방지
 *   4. Claude 호출 없음 → 순수 수학
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OddsLagDetector {

    private final BinanceWebSocketService priceMonitor;
    private final PolymarketOddsService oddsService;
    private final TradingService tradingService;
    private final BalanceService balanceService;
    private final ExpectedValueCalculator evCalculator;

    @Value("${trading.dry-run}")
    private boolean dryRun;

    // === 속도 최적화: 비동기 처리용 스레드풀 ===
    private final ExecutorService spikeExecutor = Executors.newFixedThreadPool(2);

    // === 속도 최적화: 오즈 백그라운드 캐시 (3초마다 갱신) ===
    private final ScheduledExecutorService oddsPollExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, PolymarketOddsService.MarketOdds> cachedOdds1h = new ConcurrentHashMap<>();
    private final Map<String, PolymarketOddsService.MarketOdds> cachedOdds15m = new ConcurrentHashMap<>();
    private volatile long lastOddsPollTime = 0;

    // === 쿨다운 & 제한 ===
    private final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60_000; // 같은 코인 60초 쿨다운

    private final Map<String, Integer> hourlyTradeCount = new ConcurrentHashMap<>();
    private volatile int lastHour = -1;
    private static final int MAX_TRADES_PER_HOUR = 3;

    // === 스파이크 디바운스: 같은 방향 연속 트리거 방지 ===
    private final Map<String, String> lastSpikeDirection = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSpikeTime = new ConcurrentHashMap<>();
    private static final long SPIKE_DEBOUNCE_MS = 15_000; // 같은 방향 15초 디바운스

    // === 임계값 ===
    private static final double MIN_ODDS_GAP = 0.10; // 10% 최소 갭

    @PostConstruct
    public void init() {
        // 1. WebSocket 스파이크 콜백 등록
        priceMonitor.onSpike(this::onPriceSpikeAsync);

        // 2. 오즈 백그라운드 폴링 시작 (5초마다 — 6코인×2TF)
        oddsPollExecutor.scheduleAtFixedRate(this::pollOdds, 0, 5, TimeUnit.SECONDS);

        String coinList = CoinConfig.ACTIVE_COINS.stream()
                .map(CoinConfig.CoinDef::label)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        log.info("⚡ 오즈 지연 감지기 활성화 | 코인: [{}] | 최소 갭: {}% | 쿨다운: {}초 | 시간당 최대: {}건",
                coinList, (int)(MIN_ODDS_GAP * 100), COOLDOWN_MS / 1000, MAX_TRADES_PER_HOUR);
    }

    @PreDestroy
    public void shutdown() {
        spikeExecutor.shutdownNow();
        oddsPollExecutor.shutdownNow();
    }

    // =========================================================================
    // 오즈 백그라운드 폴링 (스파이크 시 HTTP 콜 없이 즉시 사용)
    // =========================================================================
    private void pollOdds() {
        try {
            for (CoinConfig.CoinDef coin : CoinConfig.ACTIVE_COINS) {
                try {
                    PolymarketOddsService.MarketOdds odds1h = oddsService.getOddsForCoin(coin.label());
                    if (odds1h != null && odds1h.available()) {
                        cachedOdds1h.put(coin.label(), odds1h);
                    }
                    PolymarketOddsService.MarketOdds odds15m = oddsService.getOdds15mForCoin(coin.label());
                    if (odds15m != null && odds15m.available()) {
                        cachedOdds15m.put(coin.label(), odds15m);
                    }
                } catch (Exception e) {
                    log.debug("[{}] 오즈 폴링 실패: {}", coin.label(), e.getMessage());
                }
            }
            lastOddsPollTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("오즈 폴링 전체 실패: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 스파이크 콜백 (WebSocket 스레드 → 비동기 전환)
    // =========================================================================
    private void onPriceSpikeAsync(String coin, PriceSpike spike) {
        // WebSocket 스레드 블로킹 방지: 즉시 비동기 전환
        spikeExecutor.submit(() -> handleSpike(coin, spike));
    }

    // =========================================================================
    // 핵심 로직: 스파이크 → 오즈 갭 → 배팅
    // =========================================================================
    private void handleSpike(String coin, PriceSpike spike) {
        long startTime = System.currentTimeMillis();

        try {
            // --- 빠른 필터 (0ms) ---

            // 1. 디바운스: 같은 방향 15초 내 중복 방지
            String direction = spike.changePct() > 0 ? "UP" : "DOWN";
            String lastDir = lastSpikeDirection.get(coin);
            Long lastTime = lastSpikeTime.get(coin);
            if (direction.equals(lastDir) && lastTime != null
                    && (startTime - lastTime) < SPIKE_DEBOUNCE_MS) {
                return; // 같은 방향 연속 스파이크 무시
            }
            lastSpikeDirection.put(coin, direction);
            lastSpikeTime.put(coin, startTime);

            // 2. 시간당 카운트
            int currentHour = (int)(startTime / 3_600_000);
            if (currentHour != lastHour) {
                lastHour = currentHour;
                hourlyTradeCount.clear();
            }
            if (hourlyTradeCount.getOrDefault(coin, 0) >= MAX_TRADES_PER_HOUR) return;

            // 3. 쿨다운 (⭐ 공유 쿨다운 체크 — GapScanner와 이중배팅 방지)
            if (OddsGapScanner.isOnCooldown(coin, "1H")
                    && OddsGapScanner.isOnCooldown(coin, "15M")) {
                return; // 두 타임프레임 모두 쿨다운이면 스킵
            }
            Long lastTrade = lastTradeTime.get(coin);
            if (lastTrade != null && (startTime - lastTrade) < COOLDOWN_MS) return;

            // 4. 잔액
            double balance = balanceService.getBalance();
            if (balance < 1.0) return;

            // --- 오즈 갭 계산 (0ms — 캐시 사용) ---

            // 5. 캐시된 오즈 사용 (HTTP 콜 없음!)
            PolymarketOddsService.MarketOdds odds = getCachedOdds(coin);
            if (odds == null || !odds.available()) {
                log.debug("[{}] 캐시 오즈 없음, 스킵", coin);
                return;
            }

            // 오즈 캐시 신선도 체크 (8초 이상이면 스킵 — 오래된 오즈로 배팅하면 위험)
            if (System.currentTimeMillis() - lastOddsPollTime > 8_000) {
                log.debug("[{}] 오즈 캐시 만료 ({}ms), 스킵", coin,
                        System.currentTimeMillis() - lastOddsPollTime);
                return;
            }

            // ⭐ 스프레드 검증: UP+DOWN > 1.05면 유동성 부족
            double spread = odds.upOdds() + odds.downOdds();
            if (spread > 1.05) {
                log.debug("[{}] 스프레드 과다: {}%, 스킵", coin, String.format("%.1f", spread * 100));
                return;
            }

            // 6. 실제 확률 추정 & 갭 계산
            double estimatedProb = estimateRealProbability(spike.changePct());
            double marketOdds = "UP".equals(direction) ? odds.upOdds() : odds.downOdds();
            double oddsGap = estimatedProb - marketOdds;

            if (oddsGap < MIN_ODDS_GAP) {
                log.debug("[{}] 갭 부족: {}% < {}%", coin,
                        String.format("%.1f", oddsGap * 100), (int)(MIN_ODDS_GAP * 100));
                return;
            }

            // --- 배팅 실행 ---

            long decisionLatency = System.currentTimeMillis() - startTime;

            tradingService.broadcast(String.format(
                    "⚡ [%s] 오즈지연! 가격%+.3f%%(%dms) | 갭%.1f%% (추정%.0f%% vs 시장%.0f%%) | 판단%dms",
                    coin, spike.changePct(), spike.durationMs(),
                    oddsGap * 100, estimatedProb * 100, marketOdds * 100, decisionLatency));

            // 7. EV 계산
            ExpectedValueCalculator.EvResult evResult = evCalculator.calculateMomentum(
                    estimatedProb, marketOdds, direction);

            if (evResult.bestEv() <= 0) {
                tradingService.broadcast(String.format("⏸️ [%s] EV 부족: %+.1f%%", coin, evResult.bestEv() * 100));
                return;
            }

            // 8. 배팅 금액 (Kelly)
            double betAmount = evCalculator.calcBetSize(balance, evResult.bestEv(), marketOdds);
            betAmount = Math.max(betAmount, 1.0);

            Trade.TradeAction action = "UP".equals(direction)
                    ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
            String dir = action == Trade.TradeAction.BUY_YES ? "UP ⬆️" : "DOWN ⬇️";

            if (dryRun) {
                tradingService.broadcast(String.format(
                        "🟡 [DRY-RUN][%s] ⚡오즈지연 %s | $%.2f | EV:%+.1f%% | 갭:%.1f%% | %dms",
                        coin, dir, betAmount, evResult.bestEv() * 100, oddsGap * 100, decisionLatency));
            } else {
                tradingService.broadcast(String.format(
                        "🟢 [실제배팅][%s] ⚡오즈지연 %s | $%.2f | EV:%+.1f%%",
                        coin, dir, betAmount, evResult.bestEv() * 100));
            }

            // 9. 저장 & 실행
            executeLagTrade(coin, action, betAmount, spike, odds, evResult, oddsGap, estimatedProb);

            // 10. 쿨다운 & 카운트 갱신 (⭐ 공유 쿨다운도 등록)
            lastTradeTime.put(coin, System.currentTimeMillis());
            hourlyTradeCount.merge(coin, 1, Integer::sum);
            // GapScanner와 공유: 두 타임프레임 모두 쿨다운 걸기
            OddsGapScanner.registerCooldown(coin, "1H");
            OddsGapScanner.registerCooldown(coin, "15M");

            long totalLatency = System.currentTimeMillis() - startTime;
            tradingService.broadcast(String.format(
                    "✅ [%s] 오즈지연 완료 | 총%dms | 잔액:$%.2f | 이번시간:%d/%d건",
                    coin, totalLatency, balanceService.getBalance(),
                    hourlyTradeCount.get(coin), MAX_TRADES_PER_HOUR));

        } catch (Exception e) {
            log.error("[{}] 오즈 지연 처리 오류: {}", coin, e.getMessage());
        }
    }

    // =========================================================================
    // 확률 추정 & 유틸
    // =========================================================================

    /**
     * 급변동 크기 → 캔들 마감 방향 확률 추정 (보수적)
     * 10초 내 0.5% 이상 급변 = 70% 확률로 방향 유지
     */
    private double estimateRealProbability(double changePct) {
        double absPct = Math.abs(changePct);
        if (absPct >= 1.0) return 0.82;
        if (absPct >= 0.7) return 0.77;
        if (absPct >= 0.5) return 0.72;
        if (absPct >= 0.35) return 0.66;
        if (absPct >= 0.25) return 0.60;
        return 0.55;
    }

    /**
     * 캐시된 오즈 즉시 반환 — ⭐ 두 타임프레임 중 갭이 큰 쪽 선택
     */
    private PolymarketOddsService.MarketOdds getCachedOdds(String coin) {
        PolymarketOddsService.MarketOdds odds1h = cachedOdds1h.get(coin);
        PolymarketOddsService.MarketOdds odds15m = cachedOdds15m.get(coin);

        // 둘 다 있으면 더 유리한 쪽 (오즈가 더 낮은 = 저평가된 쪽)
        if (odds1h != null && odds1h.available() && odds15m != null && odds15m.available()) {
            // 15M이 유동성 낮아 더 비효율적이므로 우선
            return odds15m;
        }
        if (odds15m != null && odds15m.available()) return odds15m;
        if (odds1h != null && odds1h.available()) return odds1h;
        return null;
    }

    /**
     * 트레이드 저장 & 잔액 차감
     */
    private void executeLagTrade(String coin, Trade.TradeAction action, double betAmount,
                                  PriceSpike spike, PolymarketOddsService.MarketOdds odds,
                                  ExpectedValueCalculator.EvResult evResult,
                                  double oddsGap, double estimatedProb) {

        // ⭐ 타임프레임: 캐시된 오즈에서 어떤 마켓인지 확인
        String timeframe = (cachedOdds15m.containsKey(coin)
                && cachedOdds15m.get(coin) != null
                && cachedOdds15m.get(coin).available()
                && odds.marketId().equals(cachedOdds15m.get(coin).marketId()))
                ? "15M" : "1H";

        double mktOdds = action == Trade.TradeAction.BUY_YES ? odds.upOdds() : odds.downOdds();

        String reason = String.format("⚡오즈지연 | 가격%+.3f%%(%dms) | 갭%.1f%%(추정%.0f%% vs 오즈%.0f%%) | EV%+.1f%%",
                spike.changePct(), spike.durationMs(),
                oddsGap * 100, estimatedProb * 100, mktOdds * 100,
                evResult.bestEv() * 100);

        TradeDecision decision = TradeDecision.builder()
                .action(action)
                .confidence((int)(estimatedProb * 100))
                .amount(betAmount)
                .reason(reason)
                .rawResponse("⚡오즈지연전략 | " + reason)
                .marketId(odds.marketId())
                .marketTitle(coin + " Up or Down - " + timeframe)
                .coin(coin)
                .timeframe(timeframe)
                .build();

        MarketIndicators indicators = MarketIndicators.builder()
                .targetCoin(coin)
                .coinPrice(spike.toPrice())
                .coinHourOpen(spike.fromPrice())
                .coin15mOpen(spike.fromPrice())
                .btcPrice(priceMonitor.getPrice("BTC"))
                .ethPrice(priceMonitor.getPrice("ETH"))
                .btcChange1h(0).ethChange1h(0).ethChange4h(0).ethChange24h(0)
                .btcChange4h(0).btcChange24h(0)
                .fundingRate(0).openInterestChange(0)
                .fearGreedIndex(0).fearGreedLabel("N/A")
                .trend("SPIKE")
                .build();

        if ("BTC".equals(coin)) {
            indicators.setBtcPrice(spike.toPrice());
            indicators.setBtcHourOpen(spike.fromPrice());
        } else if ("ETH".equals(coin)) {
            indicators.setEthPrice(spike.toPrice());
            indicators.setEthHourOpen(spike.fromPrice());
        }

        tradingService.saveAndDeductLagTrade(decision, indicators, odds, evResult, betAmount, coin, timeframe);
    }
}
