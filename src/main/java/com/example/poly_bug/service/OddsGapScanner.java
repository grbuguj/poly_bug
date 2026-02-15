package com.example.poly_bug.service;

import com.example.poly_bug.config.CoinConfig;
import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * ⚡ 오즈 갭 양방향 스캐너 V5 (Math-Only Hardened)
 *
 * V4 → V5 강화 (Claude 없이 수학으로 해결):
 *  1. 횡보 감지 — 시초가 교차 횟수 추적 (3회+ = 방향 불명확 → 스킵)
 *  2. 최소 변동폭 코인별 차등 — XRP/SOL 낮은 가격 = 더 높은 % 요구
 *  3. 가격 레인지 필터 — 최근 60틱 고저차 < 기준 = 갇힌 가격 → 스킵
 *  4. 연패 서킷브레이커 — 동일 코인 3연패 → 해당 코인 5분 자동 일시정지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OddsGapScanner {

    private final BinanceWebSocketService priceMonitor;
    private final PolymarketOddsService oddsService;
    private final TradingService tradingService;
    private final BalanceService balanceService;
    private final ExpectedValueCalculator evCalculator;
    private final TradeRepository tradeRepository;

    @Value("${trading.dry-run}")
    private boolean dryRun;

    private final ScheduledExecutorService scanExecutor = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // === 공유 쿨다운 (OddsLagDetector도 체크) ===
    private static final Map<String, Long> SHARED_COOLDOWN = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 180_000; // 1H/15M: 3분
    private static final long COOLDOWN_MS_5M = 90_000; // 5M: 90초

    private static long getCooldownMs(String timeframe) {
        return "5M".equals(timeframe) ? COOLDOWN_MS_5M : COOLDOWN_MS;
    }

    /** OddsLagDetector가 이중배팅 방지용으로 체크 */
    public static boolean isOnCooldown(String coin, String timeframe) {
        Long last = SHARED_COOLDOWN.get(coin + "_" + timeframe);
        return last != null && (System.currentTimeMillis() - last) < getCooldownMs(timeframe);
    }

    /** OddsLagDetector가 배팅 후 쿨다운 등록 */
    public static void registerCooldown(String coin, String timeframe) {
        SHARED_COOLDOWN.put(coin + "_" + timeframe, System.currentTimeMillis());
    }

    private final Map<String, Integer> hourlyTradeCount = new ConcurrentHashMap<>();
    private volatile int lastHour = -1;
    private static final int MAX_TRADES_PER_COIN_PER_HOUR = 3;
    private static final int MAX_TRADES_PER_COIN_PER_HOUR_5M = 5; // 5M: 시간당 12캔들이므로

    // === 연속 갭 확인 (노이즈 필터) ===
    private final Map<String, GapStreak> gapStreaks = new ConcurrentHashMap<>();
    private static final int MIN_STREAK_SECONDS = 1; // V5: 즉시 진입 (속도=엣지)

    // === 가격 속도 추적 ===
    private final Map<String, double[]> priceVelocity = new ConcurrentHashMap<>();

    // === ⭐ NEW: 오즈 변동 추적 (역방향 과잉반응 감지) ===
    private final Map<String, double[]> oddsHistory = new ConcurrentHashMap<>(); // [이전오즈, 타임스탬프]

    // === ⭐ NEW: 모멘텀 일관성 추적 (최근 10틱 방향) ===
    private final Map<String, Deque<Integer>> momentumTicks = new ConcurrentHashMap<>(); // +1/-1 시퀀스
    private static final int MOMENTUM_WINDOW = 10;

    // === ⭐ NEW: 최근 승률 캐시 ===
    private volatile double recentWinRate = 0.50;
    private volatile long lastWinRateCheck = 0;
    private static final long WIN_RATE_CHECK_INTERVAL = 60_000; // 1분마다 갱신

    // === ⭐ V5: 횡보 감지 (시초가 교차 횟수) ===
    private final Map<String, int[]> crossCounters = new ConcurrentHashMap<>(); // [교차횟수, 마지막방향(+1/-1)]

    // === ⭐ V5: 가격 레인지 추적 (최근 60틱 고저) ===
    private final Map<String, double[]> priceRange = new ConcurrentHashMap<>(); // [min, max, tickCount]

    // === ⭐ V5: 연패 서킷브레이커 ===
    private final Map<String, Long> circuitBreakerUntil = new ConcurrentHashMap<>(); // 코인 → 해제 시각
    private volatile long lastCircuitCheck = 0;
    private static final long CIRCUIT_CHECK_INTERVAL = 30_000; // 30초마다 체크
    private static final long CIRCUIT_BREAKER_DURATION = 300_000; // 5분 정지

    // === 실시간 갭 현황 (UI 노출용) ===
    private final Map<String, GapSnapshot> latestGaps = new ConcurrentHashMap<>();

    // === ⭐ V5: 마켓 지표 캐시 (30초 갱신) ===
    private final Map<String, MarketIndicators> indicatorsCache = new ConcurrentHashMap<>();
    private volatile long lastIndicatorsRefresh = 0;
    private static final long INDICATORS_REFRESH_INTERVAL = 30_000; // 30초

    // === ⭐ V5: 실시간 활동 로그 (UI 디버그용) ===
    private final Deque<ScanLog> scanLogs = new ConcurrentLinkedDeque<>();
    private static final int MAX_SCAN_LOGS = 50;
    private final Map<String, Long> lastBoringScanLog = new ConcurrentHashMap<>(); // 반복 로그 쓰로틀

    public record ScanLog(long timestamp, String coin, String timeframe, String stage, String detail) {}

    private void addScanLog(String coin, String timeframe, String stage, String detail) {
        // 반복성 로그는 코인+TF당 5초에 1회만
        if (stage.contains("변동부족") || stage.contains("레인지")) {
            String throttleKey = coin + "_" + timeframe + "_" + stage;
            long now = System.currentTimeMillis();
            Long last = lastBoringScanLog.get(throttleKey);
            if (last != null && now - last < 5_000) return;
            lastBoringScanLog.put(throttleKey, now);
        }
        scanLogs.addFirst(new ScanLog(System.currentTimeMillis(), coin, timeframe, stage, detail));
        while (scanLogs.size() > MAX_SCAN_LOGS) scanLogs.removeLast();
    }

    public List<ScanLog> getRecentScanLogs() {
        return List.copyOf(scanLogs);
    }

    public record GapSnapshot(
            String coin, String timeframe, String direction,
            double priceDiffPct, double estimatedProb, double marketOdds,
            double gap, int streakSeconds, long timestamp,
            String reverseDirection, double reverseEstProb, double reverseMarketOdds,
            double reverseGap, int reverseStreakSeconds
    ) {}

    public Map<String, GapSnapshot> getLatestGaps() {
        return Map.copyOf(latestGaps);
    }

    // === 임계값 (동적으로 조절됨) ===
    private static final double BASE_FORWARD_GAP = 0.06; // V5: 7%→6% (밤새 0건 수정)
    private static final double BASE_REVERSE_GAP = 0.08;
    private static final double MIN_PRICE_MOVE_PCT = 0.08;
    // ⭐ V5: 코인별 최소 변동폭 (낮은 가격 코인 = 더 높은 % 요구)
    private static double getMinPriceMove(String coin, String timeframe) {
        double base = switch (coin) {
            case "BTC" -> 0.06;
            case "ETH" -> 0.08;
            case "SOL" -> 0.10;
            case "XRP" -> 0.10;
            default -> 0.10;
        };
        // 5M은 변동폭 자체가 작으므로 절반
        return "5M".equals(timeframe) ? base * 0.5 : base;
    }
    private static final double MIN_REVERSE_ODDS_THRESHOLD = 0.68;
    private static final double MIN_BALANCE = 1.0;
    private static final double MAX_SPREAD = 1.05; // ⭐ UP+DOWN > 1.05면 스킵

    // 시초가 캐시
    private final Map<String, Double> hourOpenPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> min15OpenPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> min5OpenPrices = new ConcurrentHashMap<>();
    private volatile int lastOpenHour = -1;
    private volatile int lastOpen15mWindow = -1;
    private volatile int lastOpen5mWindow = -1;
    private volatile boolean openPricesInitialized = false;

    private record GapStreak(String direction, String type, double avgGap, int count, long firstSeen) {}

    @PostConstruct
    public void init() {
        // ⭐ 시초가 Binance API에서 복구 (재시작 안전)
        initOpenPricesFromBinance();

        // 최근 승률 로드
        refreshWinRate();

        scanExecutor.scheduleAtFixedRate(this::scanAll, 5, 1, TimeUnit.SECONDS);

        String coinList = CoinConfig.ACTIVE_COINS.stream()
                .map(CoinConfig.CoinDef::label)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        log.info("🔍 오즈갭 V5 스캐너 시작 (수학강화) | 코인: [{}] | 순방향≥{}% | 역방향≥{}% | 스프레드<{}% | 승률{}% | 서킷브레이커:3연패→5분정지",
                coinList, (int)(BASE_FORWARD_GAP * 100), (int)(BASE_REVERSE_GAP * 100),
                (int)(MAX_SPREAD * 100), String.format("%.0f", recentWinRate * 100));
    }

    @PreDestroy
    public void shutdown() {
        scanExecutor.shutdownNow();
    }

    // =========================================================================
    // ⭐ NEW: Binance API에서 현재 캔들 시초가 복구
    // =========================================================================
    private void initOpenPricesFromBinance() {
        for (CoinConfig.CoinDef coinDef : CoinConfig.ACTIVE_COINS) {
            String coin = coinDef.label();
            try {
                // 1H 시초가
                double hourOpen = fetchCandleOpen(coin, "1h");
                if (hourOpen > 0) {
                    hourOpenPrices.put(coin, hourOpen);
                }

                // 15M 시초가
                double min15Open = fetchCandleOpen(coin, "15m");
                if (min15Open > 0) {
                    min15OpenPrices.put(coin, min15Open);
                }

                // 5M 시초가
                double min5Open = fetchCandleOpen(coin, "5m");
                if (min5Open > 0) {
                    min5OpenPrices.put(coin, min5Open);
                }
            } catch (Exception e) {
                log.warn("[{}] Binance 시초가 복구 실패: {}", coin, e.getMessage());
            }
        }

        ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
        lastOpenHour = nowET.getHour();
        lastOpen15mWindow = nowET.getMinute() / 15;
        lastOpen5mWindow = nowET.getMinute() / 5;
        openPricesInitialized = true;

        log.info("📊 시초가 복구 완료 | 1H: {} | 15M: {} | 5M: {}", hourOpenPrices, min15OpenPrices, min5OpenPrices);
    }

    private double fetchCandleOpen(String coin, String interval) {
        try {
            String symbol = coin + "USDT";
            String url = String.format(
                    "https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=1",
                    symbol, interval);
            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute()) {
                if (res.body() == null) return 0;
                JsonNode data = objectMapper.readTree(res.body().string());
                if (!data.isArray() || data.isEmpty()) return 0;
                return data.get(0).get(1).asDouble(); // [1] = open price
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    // ⭐ NEW: 최근 승률 조회 (동적 임계값용)
    // =========================================================================
    private void refreshWinRate() {
        try {
            Long wins = tradeRepository.countWins();
            Long resolved = tradeRepository.countResolved();
            if (resolved != null && resolved >= 5) {
                recentWinRate = (double) wins / resolved;
            } else {
                recentWinRate = 0.50; // 데이터 부족 시 중립
            }
            lastWinRateCheck = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("승률 조회 실패: {}", e.getMessage());
        }
    }

    /**
     * 승률 기반 동적 임계값 조절
     * 60%+ 승률 → 임계값 -2% (공격적)
     * 50-60% → 기본
     * 40-50% → 임계값 +3% (보수적)
     * 40%- → 임계값 +5% (방어적)
     */
    private double getAdaptiveGap(double baseGap) {
        if (recentWinRate >= 0.65) return baseGap - 0.02;
        if (recentWinRate >= 0.55) return baseGap;
        if (recentWinRate >= 0.45) return baseGap + 0.03;
        return baseGap + 0.05; // 40%미만 → 매우 보수적
    }

    // =========================================================================
    // 매초 전체 스캔
    // =========================================================================
    private void scanAll() {
        try {
            // 승률 주기적 갱신
            if (System.currentTimeMillis() - lastWinRateCheck > WIN_RATE_CHECK_INTERVAL) {
                refreshWinRate();
            }

            // ⭐ V5: 연패 서킷브레이커 주기적 체크
            if (System.currentTimeMillis() - lastCircuitCheck > CIRCUIT_CHECK_INTERVAL) {
                checkCircuitBreakers();
                lastCircuitCheck = System.currentTimeMillis();
            }

            updateOpenPrices();
            for (CoinConfig.CoinDef coinDef : CoinConfig.ACTIVE_COINS) {
                String coin = coinDef.label();
                try {
                    // ⭐ V5: 서킷브레이커 발동 중이면 스킵
                    Long breakUntil = circuitBreakerUntil.get(coin);
                    if (breakUntil != null && System.currentTimeMillis() < breakUntil) {
                        addScanLog(coin, "-", "🔴 서킷브레이커",
                                String.format("%.0f초 남음", (breakUntil - System.currentTimeMillis()) / 1000.0));
                        continue;
                    }

                    scanCoin(coin, "1H");
                    scanCoin(coin, "15M");
                    scanCoin(coin, "5M");
                } catch (Exception e) {
                    log.debug("[{}] 스캔 오류: {}", coin, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("스캔 전체 오류: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 코인 × 타임프레임 개별 스캔 (양방향)
    // =========================================================================
    private void scanCoin(String coin, String timeframe) {
        double currentPrice = priceMonitor.getPrice(coin);
        if (currentPrice <= 0) return;

        double openPrice = "5M".equals(timeframe)
                ? min5OpenPrices.getOrDefault(coin, 0.0)
                : "15M".equals(timeframe)
                ? min15OpenPrices.getOrDefault(coin, 0.0)
                : hourOpenPrices.getOrDefault(coin, 0.0);
        if (openPrice <= 0) return;

        // ⚠️ 시초가 검증: 15M/5M open이 1H open과 동일하면 캐시 오염 가능성 → Binance API로 재조회
        if ("15M".equals(timeframe) || "5M".equals(timeframe)) {
            double hourOpen = hourOpenPrices.getOrDefault(coin, 0.0);
            if (openPrice == hourOpen && hourOpen > 0) {
                String interval = "15M".equals(timeframe) ? "15m" : "5m";
                double freshOpen = fetchCandleOpen(coin, interval);
                if (freshOpen > 0 && Math.abs(freshOpen - hourOpen) / hourOpen > 0.0001) {
                    // 실제로 다른 값이 있음 → 캐시 오염이었음
                    if ("15M".equals(timeframe)) {
                        min15OpenPrices.put(coin, freshOpen);
                    } else {
                        min5OpenPrices.put(coin, freshOpen);
                    }
                    openPrice = freshOpen;
                    log.warn("[{}][{}] 시초가 캐시 오염 수정: {} → {} (1H open={})",
                            coin, timeframe, hourOpen, freshOpen, hourOpen);
                }
            }
        }

        double priceDiffPct = ((currentPrice - openPrice) / openPrice) * 100;

        // 모멘텀 일관성 추적
        double velocity = trackVelocity(coin, currentPrice);
        trackMomentum(coin, priceDiffPct);

        // ⭐ V5: 횡보 감지 (시초가 교차 횟수 추적)
        trackCrossCount(coin + "_" + timeframe, priceDiffPct);

        // ⭐ V5: 가격 레인지 추적
        trackPriceRange(coin + "_" + timeframe, currentPrice);

        // ⭐ V5: 코인별 최소 변동폭 (기존 고정 0.08% → 코인별 차등)
        double minMove = getMinPriceMove(coin, timeframe);
        if (Math.abs(priceDiffPct) < minMove) {
            clearStreak(coin, timeframe, "FWD");
            clearStreak(coin, timeframe, "REV");
            addScanLog(coin, timeframe, "⏸ 변동부족",
                    String.format("%.3f%% < %.2f%%", Math.abs(priceDiffPct), minMove));
            // V5: 스냅샷은 유지 (UI 깜빡임 방지) — 갭만 0으로
            updateSnapshot(coin, timeframe, priceDiffPct, 0, 0, 0,
                    0, 0, 0, priceDiffPct > 0 ? "UP" : "DOWN",
                    priceDiffPct > 0 ? "DOWN" : "UP", null);
            return;
        }

        // ⭐ V5: 횡보 필터 — 시초가 3회+ 교차 = 방향 불명확
        int crosses = getCrossCount(coin + "_" + timeframe);
        if (crosses >= 5) {
            addScanLog(coin, timeframe, "⏸ 횡보", String.format("교차 %d회", crosses));
            log.debug("[{}][{}] 횡보 감지: 시초가 {}회 교차 — 스킵", coin, timeframe, crosses);
            clearStreak(coin, timeframe, "FWD");
            clearStreak(coin, timeframe, "REV");
            return;
        }

        // ⭐ V5: 가격 레인지 필터 — 최근 60틱 고저차가 너무 좁으면 갇힌 가격
        double rangePct = getPriceRangePct(coin + "_" + timeframe);
        if (rangePct > 0 && rangePct < minMove * 0.8) {
            addScanLog(coin, timeframe, "⏸ 레인지좁음",
                    String.format("%.3f%% < %.3f%%", rangePct, minMove * 0.8));
            log.debug("[{}][{}] 레인지 과소: {}% < {}% — 스킵",
                    coin, timeframe, String.format("%.3f", rangePct), String.format("%.3f", minMove * 0.8));
            clearStreak(coin, timeframe, "FWD");
            clearStreak(coin, timeframe, "REV");
            return;
        }

        // 타임윈도우 필터
        int candlePosition = getCandlePosition(timeframe);

        // 오즈 조회
        PolymarketOddsService.MarketOdds odds = "5M".equals(timeframe)
                ? oddsService.getOdds5mForCoin(coin)
                : "15M".equals(timeframe)
                ? oddsService.getOdds15mForCoin(coin)
                : oddsService.getOddsForCoin(coin);
        if (odds == null || !odds.available()) {
            addScanLog(coin, timeframe, "⏸ 오즈없음", "마켓 비활성");
            return;
        }

        // ⭐ NEW: 스프레드 검증
        double spread = odds.upOdds() + odds.downOdds();
        if (spread > MAX_SPREAD) {
            addScanLog(coin, timeframe, "⏸ 스프레드",
                    String.format("%.1f%% > %d%%", spread * 100, (int)(MAX_SPREAD * 100)));
            log.debug("[{}][{}] 스프레드 과다: {}% > {}% — 스킵",
                    coin, timeframe, String.format("%.1f", spread * 100), (int)(MAX_SPREAD * 100));
            return;
        }

        // 방향 판단 & 확률 추정
        String priceDir = priceDiffPct > 0 ? "UP" : "DOWN";
        double momentumScore = getMomentumConsistency(coin);
        double estimatedProb = estimateProbFromPriceMove(priceDiffPct, timeframe, velocity, momentumScore);

        // 순방향 오즈
        double fwdMarketOdds = "UP".equals(priceDir) ? odds.upOdds() : odds.downOdds();
        double fwdGap = estimatedProb - fwdMarketOdds;

        // 역방향 오즈
        String reverseDir = "UP".equals(priceDir) ? "DOWN" : "UP";
        double reverseEstProb = 1.0 - estimatedProb;
        double reverseMarketOdds = "UP".equals(reverseDir) ? odds.upOdds() : odds.downOdds();
        double reverseGap = reverseEstProb - reverseMarketOdds;

        // ⭐ NEW: 오즈 변동 추적 (과잉반응 보너스)
        double oddsVelocity = trackOddsVelocity(coin + "_" + timeframe, fwdMarketOdds);

        // UI 스냅샷
        updateSnapshot(coin, timeframe, priceDiffPct,
                estimatedProb, fwdMarketOdds, fwdGap,
                reverseEstProb, reverseMarketOdds, reverseGap,
                priceDir, reverseDir, odds);

        // 동적 임계값
        double adaptiveFwdGap = getAdaptiveGap(BASE_FORWARD_GAP);
        double adaptiveRevGap = getAdaptiveGap(BASE_REVERSE_GAP);

        // === 순방향 체크 ===
        if (fwdGap >= adaptiveFwdGap && candlePosition >= 1 && candlePosition <= 3) {
            addScanLog(coin, timeframe, "🔍 순방향갭!",
                    String.format("%s 갭%.1f%%≥%.1f%% 추정%.0f%% vs 오즈%.0f%%",
                            priceDir, fwdGap * 100, adaptiveFwdGap * 100,
                            estimatedProb * 100, fwdMarketOdds * 100));
            checkAndTradeFwd(coin, timeframe, priceDir, fwdGap,
                    priceDiffPct, estimatedProb, fwdMarketOdds, odds, momentumScore);
        } else {
            addScanLog(coin, timeframe, "⏸ 갭부족",
                    String.format("%s 갭%.1f%% < %.1f%% | 캔들%d",
                            priceDir, fwdGap * 100, adaptiveFwdGap * 100, candlePosition));
            clearStreak(coin, timeframe, "FWD");
        }

        // === 역방향 체크 ===
        // ⭐ 오즈가 급변했으면 역방향 임계값 2% 추가 완화
        double revThreshold = adaptiveRevGap;
        if (Math.abs(oddsVelocity) >= 0.02) { // 초당 2%+ 오즈 변동 → 과잉반응
            revThreshold -= 0.02;
        }

        // === 역방향 비활성화 (V5: 데이터가 증명할 때까지 OFF) ===
        // 역방향 EV가 구조적으로 뻥튀기됨 (낮은 오즈로 나누면 항상 100%+)
        // 순방향만으로 승률 검증 후 재활성화 예정
        /*
        if (fwdMarketOdds >= MIN_REVERSE_ODDS_THRESHOLD
                && reverseGap >= revThreshold
                && candlePosition >= 2 && candlePosition <= 3) {
            checkAndTradeRev(coin, timeframe, reverseDir, reverseGap,
                    priceDiffPct, reverseEstProb, reverseMarketOdds, odds, oddsVelocity);
        } else {
            clearStreak(coin, timeframe, "REV");
        }
        */
        clearStreak(coin, timeframe, "REV");
    }

    // =========================================================================
    // ⭐ NEW: 모멘텀 일관성 추적
    // =========================================================================
    private void trackMomentum(String coin, double priceDiffPct) {
        Deque<Integer> ticks = momentumTicks.computeIfAbsent(coin,
                k -> new ConcurrentLinkedDeque<>());
        ticks.addLast(priceDiffPct >= 0 ? 1 : -1);
        while (ticks.size() > MOMENTUM_WINDOW) ticks.pollFirst();
    }

    /**
     * 모멘텀 일관성: -1.0 ~ +1.0
     * +1.0 = 10틱 전부 UP
     * -1.0 = 10틱 전부 DOWN
     * 0.0 = 반반
     */
    private double getMomentumConsistency(String coin) {
        Deque<Integer> ticks = momentumTicks.get(coin);
        if (ticks == null || ticks.size() < 3) return 0.0;
        int sum = 0;
        for (int t : ticks) sum += t;
        return (double) sum / ticks.size();
    }

    // =========================================================================
    // ⭐ V5: 횡보 감지 — 시초가 교차 횟수 추적
    // 가격이 시초가 위↔아래로 왔다갔다하면 방향 불명확
    // =========================================================================
    private void trackCrossCount(String key, double priceDiffPct) {
        int currentDir = priceDiffPct >= 0 ? 1 : -1;
        int[] state = crossCounters.get(key);

        if (state == null) {
            crossCounters.put(key, new int[]{0, currentDir});
            return;
        }

        if (state[1] != currentDir) {
            // 방향 전환 = 교차 발생
            state[0]++;
            state[1] = currentDir;
        }
    }

    private int getCrossCount(String key) {
        int[] state = crossCounters.get(key);
        return state != null ? state[0] : 0;
    }

    // =========================================================================
    // ⭐ V5: 가격 레인지 추적 — 최근 60틱 고저차
    // 고저차가 너무 좁으면 "갇힌 가격" → 방향성 없음
    // =========================================================================
    private void trackPriceRange(String key, double price) {
        double[] range = priceRange.get(key);
        if (range == null) {
            priceRange.put(key, new double[]{price, price, 1});
            return;
        }

        range[0] = Math.min(range[0], price); // min
        range[1] = Math.max(range[1], price); // max
        range[2]++;

        // 60틱마다 리셋 (약 60초)
        if (range[2] > 60) {
            priceRange.put(key, new double[]{price, price, 1});
        }
    }

    /** 가격 레인지를 %로 반환 */
    private double getPriceRangePct(String key) {
        double[] range = priceRange.get(key);
        if (range == null || range[2] < 10 || range[0] <= 0) return -1; // 데이터 부족
        return ((range[1] - range[0]) / range[0]) * 100;
    }

    // =========================================================================
    // ⭐ V5: 연패 서킷브레이커
    // 동일 코인 3연패 → 5분 자동 정지
    // =========================================================================
    private void checkCircuitBreakers() {
        try {
            for (CoinConfig.CoinDef coinDef : CoinConfig.ACTIVE_COINS) {
                String coin = coinDef.label();
                List<Trade> recent = tradeRepository.findRecent10ResolvedByCoin(coin);
                if (recent.size() < 3) continue;

                // 최근 3건 연속 LOSE 체크
                boolean threeConsecLoss = recent.stream().limit(3)
                        .allMatch(t -> t.getResult() == Trade.TradeResult.LOSE);

                if (threeConsecLoss) {
                    Long existing = circuitBreakerUntil.get(coin);
                    long now = System.currentTimeMillis();
                    if (existing == null || now >= existing) {
                        circuitBreakerUntil.put(coin, now + CIRCUIT_BREAKER_DURATION);
                        tradingService.broadcast(String.format(
                                "🔴 서킷브레이커 [%s] 3연패 감지 → 5분 정지 (%.0f%% 승률)",
                                coin, recentWinRate * 100));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("서킷브레이커 체크 오류: {}", e.getMessage());
        }
    }

    // =========================================================================
    // ⭐ NEW: 오즈 변동 속도 추적 (과잉반응 감지용)
    // =========================================================================
    private double trackOddsVelocity(String key, double currentOdds) {
        double[] prev = oddsHistory.get(key);
        long now = System.currentTimeMillis();

        if (prev == null) {
            oddsHistory.put(key, new double[]{currentOdds, now});
            return 0.0;
        }

        double elapsed = (now - prev[1]) / 1000.0;
        if (elapsed <= 0) return 0.0;

        double velocity = (currentOdds - prev[0]) / elapsed; // 오즈/초
        oddsHistory.put(key, new double[]{currentOdds, now});
        return velocity;
    }

    // =========================================================================
    // 순방향 배팅 (시장이 늦은 경우)
    // =========================================================================
    private void checkAndTradeFwd(String coin, String timeframe, String betDir,
                                   double gap, double priceDiffPct,
                                   double estProb, double mktOdds,
                                   PolymarketOddsService.MarketOdds odds,
                                   double momentumScore) {
        String key = coin + "_" + timeframe + "_FWD";
        long now = System.currentTimeMillis();

        GapStreak streak = gapStreaks.get(key);
        if (streak == null || !streak.direction.equals(betDir)) {
            gapStreaks.put(key, new GapStreak(betDir, "FWD", gap, 1, now));
            // V5: 즉시 진입 — 첫 감지에서 바로 통과
        } else {
            double newAvgGap = (streak.avgGap * streak.count + gap) / (streak.count + 1);
            int cnt = streak.count + 1;
            gapStreaks.put(key, new GapStreak(betDir, "FWD", newAvgGap, cnt, streak.firstSeen));
        }

        int newCount = gapStreaks.get(key).count;
        double avgGap = gapStreaks.get(key).avgGap;

        if (newCount < MIN_STREAK_SECONDS) {
            addScanLog(coin, timeframe, "⏳ 연속대기", String.format("%d/%d초", newCount, MIN_STREAK_SECONDS));
            return;
        }

        // ⭐ 모멘텀 일관성 체크: 방향이 혼재하면 스킵
        double absMomentum = Math.abs(momentumScore);
        if (absMomentum < 0.4) {
            addScanLog(coin, timeframe, "⏸ 모멘텀약",
                    String.format("%.0f%% < 40%%", absMomentum * 100));
            log.debug("[{}][{}] 모멘텀 불안정: {} < 0.4 — 스킵", coin, timeframe,
                    String.format("%.2f", absMomentum));
            return;
        }

        // 공유 쿨다운
        if (isOnCooldown(coin, timeframe)) {
            addScanLog(coin, timeframe, "⏸ 쿨다운",
                    "5M".equals(timeframe) ? "90초 대기중" : "3분 대기중");
            return;
        }
        if (!checkHourlyLimit(coin + "_" + timeframe, timeframe)) {
            addScanLog(coin, timeframe, "⏸ 시간한도", "시간당 3건 초과");
            return;
        }

        double balance = balanceService.getBalance();
        if (balance < MIN_BALANCE) {
            addScanLog(coin, timeframe, "⏸ 잔액부족", String.format("$%.2f", balance));
            return;
        }

        ExpectedValueCalculator.EvResult evResult = evCalculator.calculateMomentum(
                estProb, mktOdds, betDir);
        if (evResult.bestEv() <= 0) {
            addScanLog(coin, timeframe, "⏸ EV부족", String.format("EV %.1f%%", evResult.bestEv() * 100));
            return;
        }

        double betAmount = evCalculator.calcBetSize(balance, evResult.bestEv(), mktOdds);
        betAmount = Math.max(betAmount, 1.0);

        Trade.TradeAction action = "UP".equals(betDir)
                ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
        String dir = action == Trade.TradeAction.BUY_YES ? "UP ⬆️" : "DOWN ⬇️";

        tradingService.broadcast(String.format(
                "🔍순방향 [%s][%s] %s | 가격%+.2f%% | 갭%.1f%% | EV%+.1f%% | $%.2f | %d초 | 모멘텀%.0f%% | 승률%.0f%%",
                coin, timeframe, dir, priceDiffPct,
                avgGap * 100, evResult.bestEv() * 100, betAmount, newCount,
                absMomentum * 100, recentWinRate * 100));

        addScanLog(coin, timeframe, "✅ 배팅!",
                String.format("%s $%.2f EV%+.1f%%", dir, betAmount, evResult.bestEv() * 100));

        executeTrade(coin, timeframe, action, betAmount, priceDiffPct, odds, evResult,
                avgGap, estProb, "🔍순방향");

        registerCooldown(coin, timeframe);
        hourlyTradeCount.merge(coin + "_" + timeframe, 1, Integer::sum);
        clearStreak(coin, timeframe, "FWD");
    }

    // =========================================================================
    // 역방향 배팅 (시장 과잉반응)
    // =========================================================================
    private void checkAndTradeRev(String coin, String timeframe, String betDir,
                                   double gap, double priceDiffPct,
                                   double estProb, double mktOdds,
                                   PolymarketOddsService.MarketOdds odds,
                                   double oddsVelocity) {
        String key = coin + "_" + timeframe + "_REV";
        long now = System.currentTimeMillis();

        GapStreak streak = gapStreaks.get(key);
        if (streak == null || !streak.direction.equals(betDir)) {
            gapStreaks.put(key, new GapStreak(betDir, "REV", gap, 1, now));
            return;
        }

        double newAvgGap = (streak.avgGap * streak.count + gap) / (streak.count + 1);
        int newCount = streak.count + 1;
        gapStreaks.put(key, new GapStreak(betDir, "REV", newAvgGap, newCount, streak.firstSeen));

        // 역방향은 4초 연속
        if (newCount < 4) return;

        if (isOnCooldown(coin, timeframe)) {
            addScanLog(coin, timeframe, "⏸ 쿨다운",
                    "5M".equals(timeframe) ? "90초 대기중" : "3분 대기중");
            return;
        }
        if (!checkHourlyLimit(coin + "_" + timeframe, timeframe)) {
            addScanLog(coin, timeframe, "⏸ 시간한도", "시간당 3건 초과");
            return;
        }

        double balance = balanceService.getBalance();
        if (balance < MIN_BALANCE) {
            addScanLog(coin, timeframe, "⏸ 잔액부족", String.format("$%.2f", balance));
            return;
        }

        // ⭐ 오즈 급변 시 확률 보정: 과잉반응 확인
        double adjustedEstProb = estProb;
        if (Math.abs(oddsVelocity) >= 0.03) { // 초당 3%+ 오즈 변동
            adjustedEstProb += 0.03; // 반대쪽 확률 3% 추가
            adjustedEstProb = Math.min(adjustedEstProb, 0.55);
        }

        ExpectedValueCalculator.EvResult evResult = evCalculator.calculateReverse(
                adjustedEstProb, mktOdds, betDir);
        if (evResult.bestEv() <= 0) {
            addScanLog(coin, timeframe, "⏸ EV부족", String.format("EV %.1f%%", evResult.bestEv() * 100));
            return;
        }

        double betAmount = evCalculator.calcReverseBetSize(balance, evResult.bestEv(), mktOdds);
        betAmount = Math.max(betAmount, 1.0);

        Trade.TradeAction action = "UP".equals(betDir)
                ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
        String dir = action == Trade.TradeAction.BUY_YES ? "UP ⬆️" : "DOWN ⬇️";

        String oddsVelStr = Math.abs(oddsVelocity) >= 0.02
                ? String.format(" | 오즈속도%+.1f%%/s", oddsVelocity * 100)
                : "";

        tradingService.broadcast(String.format(
                "🔄역방향 [%s][%s] %s | 가격%+.2f%% | 갭%.1f%% | 추정%.0f%% vs 오즈%.0f¢ | EV%+.1f%% | $%.2f | %d초%s",
                coin, timeframe, dir, priceDiffPct,
                newAvgGap * 100, adjustedEstProb * 100, mktOdds * 100,
                evResult.bestEv() * 100, betAmount, newCount, oddsVelStr));

        executeTrade(coin, timeframe, action, betAmount, priceDiffPct, odds, evResult,
                newAvgGap, adjustedEstProb, "🔄역방향");

        registerCooldown(coin, timeframe);
        hourlyTradeCount.merge(coin + "_" + timeframe, 1, Integer::sum);
        clearStreak(coin, timeframe, "REV");
    }

    // =========================================================================
    // 가격 변동 → 확률 추정 V4 (속도 + 일관성 + 시간)
    // =========================================================================
    private double estimateProbFromPriceMove(double changePct, String timeframe,
                                              double velocity, double momentumScore) {
        double absPct = Math.abs(changePct);
        boolean is5m = "5M".equals(timeframe);
        boolean is15m = "15M".equals(timeframe);

        double timeBonus = getTimeBonus(timeframe);
        double tfBonus = is5m ? 0.05 : is15m ? 0.03 : 0.0;

        // 속도 보너스
        double velocityBonus = 0.0;
        double absVelocity = Math.abs(velocity);
        if (absVelocity >= 0.05)      velocityBonus = 0.06;
        else if (absVelocity >= 0.02) velocityBonus = 0.04;
        else if (absVelocity >= 0.01) velocityBonus = 0.02;

        if ((changePct > 0 && velocity < 0) || (changePct < 0 && velocity > 0)) {
            velocityBonus = -0.02;
        }

        // ⭐ NEW: 모멘텀 일관성 보너스
        double momentumBonus = 0.0;
        double absMomentum = Math.abs(momentumScore);
        if (absMomentum >= 0.8) momentumBonus = 0.04; // 8/10+ 같은 방향
        else if (absMomentum >= 0.6) momentumBonus = 0.02; // 6/10+
        else if (absMomentum < 0.3) momentumBonus = -0.02; // 혼재 → 페널티

        double bonus = tfBonus + timeBonus + velocityBonus + momentumBonus;

        double baseProb;
        if (absPct >= 1.0)       baseProb = 0.85;
        else if (absPct >= 0.7)  baseProb = 0.80;
        else if (absPct >= 0.5)  baseProb = 0.73;
        else if (absPct >= 0.35) baseProb = 0.66;
        else if (absPct >= 0.25) baseProb = 0.61;
        else if (absPct >= 0.15) baseProb = 0.57;
        else if (absPct >= 0.10) baseProb = 0.54;
        else if (absPct >= 0.08) baseProb = 0.52;
        else                     baseProb = 0.51;

        return Math.min(Math.max(baseProb + bonus, 0.50), 0.92);
    }

    // =========================================================================
    // 가격 속도 추적 (%/초)
    // =========================================================================
    private double trackVelocity(String coin, double currentPrice) {
        double[] prev = priceVelocity.get(coin);
        long now = System.currentTimeMillis();

        if (prev == null) {
            priceVelocity.put(coin, new double[]{currentPrice, now});
            return 0.0;
        }

        double prevPrice = prev[0];
        double prevTime = prev[1];
        double elapsed = (now - prevTime) / 1000.0;

        priceVelocity.put(coin, new double[]{currentPrice, now});

        if (elapsed <= 0 || prevPrice <= 0) return 0.0;
        return ((currentPrice - prevPrice) / prevPrice * 100) / elapsed;
    }

    // =========================================================================
    // 캔들 포지션 판단
    // =========================================================================
    private int getCandlePosition(String timeframe) {
        ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int minute = nowET.getMinute();
        int second = nowET.getSecond();

        if ("5M".equals(timeframe)) {
            int elapsed = (minute % 5) * 60 + second;
            int total = 5 * 60; // 300초
            int remaining = total - elapsed;

            if (elapsed < 40) return 0;   // 시작 40초 제외
            if (remaining < 40) return 4; // 마감 40초 제외
            double pct = (double)elapsed / total;
            if (pct < 0.30) return 1;
            if (pct < 0.70) return 2;
            return 3;
        } else if ("15M".equals(timeframe)) {
            int elapsed = (minute % 15) * 60 + second;
            int total = 15 * 60;
            int remaining = total - elapsed;

            if (elapsed < 120) return 0;
            if (remaining < 120) return 4;
            double pct = (double)elapsed / total;
            if (pct < 0.30) return 1;
            if (pct < 0.70) return 2;
            return 3;
        } else {
            int elapsed = minute * 60 + second;
            int total = 60 * 60;
            int remaining = total - elapsed;

            if (elapsed < 180) return 0;
            if (remaining < 180) return 4;
            double pct = (double)elapsed / total;
            if (pct < 0.30) return 1;
            if (pct < 0.70) return 2;
            return 3;
        }
    }

    private double getTimeBonus(String timeframe) {
        ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int minute = nowET.getMinute();

        if ("5M".equals(timeframe)) {
            int elapsed = minute % 5;
            if (elapsed >= 4) return 0.07; // 거의 확정
            if (elapsed >= 3) return 0.05;
            if (elapsed >= 2) return 0.03;
            if (elapsed >= 1) return 0.01;
            return 0.0;
        } else if ("15M".equals(timeframe)) {
            int elapsed = minute % 15;
            if (elapsed >= 12) return 0.07;
            if (elapsed >= 10) return 0.05;
            if (elapsed >= 7)  return 0.03;
            if (elapsed >= 4)  return 0.01;
            return 0.0;
        } else {
            if (minute >= 50) return 0.07;
            if (minute >= 40) return 0.05;
            if (minute >= 30) return 0.03;
            if (minute >= 15) return 0.01;
            return 0.0;
        }
    }

    // =========================================================================
    // 유틸
    // =========================================================================
    private boolean checkHourlyLimit(String cooldownKey, String timeframe) {
        long now = System.currentTimeMillis();
        int currentHour = (int)(now / 3_600_000);
        if (currentHour != lastHour) {
            lastHour = currentHour;
            hourlyTradeCount.clear();
        }
        int limit = "5M".equals(timeframe) ? MAX_TRADES_PER_COIN_PER_HOUR_5M : MAX_TRADES_PER_COIN_PER_HOUR;
        return hourlyTradeCount.getOrDefault(cooldownKey, 0) < limit;
    }

    private void updateSnapshot(String coin, String timeframe, double priceDiffPct,
                                 double estProb, double mktOdds, double gap,
                                 double revEstProb, PolymarketOddsService.MarketOdds odds) {
        String key = coin + "_" + timeframe;
        latestGaps.put(key, new GapSnapshot(
                coin, timeframe, priceDiffPct > 0 ? "UP" : "DOWN", priceDiffPct,
                estProb, mktOdds, gap, 0, System.currentTimeMillis(),
                "", 0, 0, 0, 0));
    }

    private void updateSnapshot(String coin, String timeframe, double priceDiffPct,
                                 double estProb, double fwdMktOdds, double fwdGap,
                                 double revEstProb, double revMktOdds, double revGap,
                                 String priceDir, String reverseDir,
                                 PolymarketOddsService.MarketOdds odds) {
        String key = coin + "_" + timeframe;
        String fwdKey = key + "_FWD";
        String revKey = key + "_REV";
        int fwdStreak = gapStreaks.containsKey(fwdKey) ? gapStreaks.get(fwdKey).count : 0;
        int revStreak = gapStreaks.containsKey(revKey) ? gapStreaks.get(revKey).count : 0;

        latestGaps.put(key, new GapSnapshot(
                coin, timeframe, priceDir, priceDiffPct,
                estProb, fwdMktOdds, fwdGap, fwdStreak, System.currentTimeMillis(),
                reverseDir, revEstProb, revMktOdds, revGap, revStreak));
    }

    private void updateOpenPrices() {
        ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int currentHour = nowET.getHour();
        int current15mWindow = nowET.getMinute() / 15;
        int current5mWindow = nowET.getMinute() / 5;

        if (currentHour != lastOpenHour) {
            lastOpenHour = currentHour;
            for (CoinConfig.CoinDef coin : CoinConfig.ACTIVE_COINS) {
                double price = priceMonitor.getPrice(coin.label());
                if (price > 0) hourOpenPrices.put(coin.label(), price);
                crossCounters.remove(coin.label() + "_1H");
                priceRange.remove(coin.label() + "_1H");
            }
            log.info("⏰ 1H 시초가 갱신: {}", hourOpenPrices);
        }

        if (current15mWindow != lastOpen15mWindow) {
            lastOpen15mWindow = current15mWindow;
            for (CoinConfig.CoinDef coin : CoinConfig.ACTIVE_COINS) {
                double price = priceMonitor.getPrice(coin.label());
                if (price <= 0) {
                    // WebSocket 실패 시 Binance API fallback
                    price = fetchCandleOpen(coin.label(), "15m");
                    log.warn("[{}] 15M WebSocket 가격 없음 → Binance API fallback: {}", coin.label(), price);
                }
                if (price > 0) min15OpenPrices.put(coin.label(), price);
                crossCounters.remove(coin.label() + "_15M");
                priceRange.remove(coin.label() + "_15M");
            }
            log.info("⏰ 15M 시초가 갱신: {}", min15OpenPrices);
        }

        if (current5mWindow != lastOpen5mWindow) {
            lastOpen5mWindow = current5mWindow;
            for (CoinConfig.CoinDef coin : CoinConfig.ACTIVE_COINS) {
                double price = priceMonitor.getPrice(coin.label());
                if (price <= 0) {
                    // WebSocket 실패 시 Binance API fallback
                    price = fetchCandleOpen(coin.label(), "5m");
                    log.warn("[{}] 5M WebSocket 가격 없음 → Binance API fallback: {}", coin.label(), price);
                }
                if (price > 0) min5OpenPrices.put(coin.label(), price);
                crossCounters.remove(coin.label() + "_5M");
                priceRange.remove(coin.label() + "_5M");
            }
            log.info("⏰ 5M 시초가 갱신: {}", min5OpenPrices);
        }
    }

    private void clearStreak(String coin, String timeframe, String type) {
        gapStreaks.remove(coin + "_" + timeframe + "_" + type);
    }

    // =========================================================================
    // 트레이드 실행
    // =========================================================================
    private void executeTrade(String coin, String timeframe, Trade.TradeAction action,
                              double betAmount, double priceDiffPct,
                              PolymarketOddsService.MarketOdds odds,
                              ExpectedValueCalculator.EvResult evResult,
                              double gap, double estimatedProb, String label) {

        double mktOdds = action == Trade.TradeAction.BUY_YES ? odds.upOdds() : odds.downOdds();

        String reason = String.format("%s | 가격%+.2f%% | 갭%.1f%%(추정%.0f%% vs 오즈%.0f%%) | EV%+.1f%% | 승률%.0f%%",
                label, priceDiffPct, gap * 100, estimatedProb * 100, mktOdds * 100,
                evResult.bestEv() * 100, recentWinRate * 100);

        TradeDecision decision = TradeDecision.builder()
                .action(action)
                .confidence((int)(estimatedProb * 100))
                .amount(betAmount)
                .reason(reason)
                .rawResponse(label + "전략V5 | " + reason)
                .marketId(odds.marketId())
                .marketTitle(coin + " Up or Down - " + timeframe)
                .coin(coin)
                .timeframe(timeframe)
                .build();

        MarketIndicators indicators = MarketIndicators.builder()
                .targetCoin(coin)
                .coinPrice(priceMonitor.getPrice(coin))
                .coinHourOpen(hourOpenPrices.getOrDefault(coin, 0.0))
                .coin15mOpen(min15OpenPrices.getOrDefault(coin, 0.0))
                .coin5mOpen(min5OpenPrices.getOrDefault(coin, 0.0))
                .btcPrice(priceMonitor.getPrice("BTC"))
                .ethPrice(priceMonitor.getPrice("ETH"))
                .btcChange1h(0).ethChange1h(0).ethChange4h(0).ethChange24h(0)
                .btcChange4h(0).btcChange24h(0)
                .fundingRate(0).openInterestChange(0)
                .fearGreedIndex(0).fearGreedLabel("N/A")
                .trend("GAP_SCAN_V5")
                .build();

        tradingService.saveAndDeductLagTrade(decision, indicators, odds, evResult, betAmount, coin, timeframe);
    }
}
