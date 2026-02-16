package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.util.PriceFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService {

    private final MarketDataService marketDataService;
    private final ClaudeDecisionEngine claudeEngine;
    private final SelfReflectionService reflectionService;
    private final TradeRepository tradeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BotStateService botStateService;
    private final PolymarketOddsService oddsService;
    private final ExpectedValueCalculator evCalculator;
    private final PolymarketOrderService orderService;
    private final BalanceService balanceService;
    private final ChainlinkPriceService chainlinkPriceService;

    @Value("${trading.dry-run}")
    private boolean dryRun;

    /**
     * 🚀 모멘텀 추종 전략 (1H 메인)
     * 핵심: 방향은 가격이 결정, Claude는 반전 체크만
     *
     * 1. 시초가 vs 현재가 → 방향 결정
     * 2. 변동폭 0.3%+ 필요 (노이즈 필터)
     * 3. Claude → 반전 위험 체크 (거부권만)
     * 4. EV → 모멘텀 역사 승률 기반 (Claude confidence 안 씀)
     */
    public boolean executeMomentumCycle(String coin, String timeframe, double minEvThreshold) {
        String tfLabel = timeframe;
        broadcast(String.format("🔄 [%s %s] 모멘텀 분석 시작...", coin, tfLabel));
        try {
            // 1. 시장 데이터 수집
            MarketIndicators indicators = marketDataService.collect(coin);
            boolean is15m = "15M".equals(timeframe);
            String symbol = coin + "USDT";

            double currentPrice = indicators.getCoinPrice();

            // 2. 시초가 조회 (5M/15M은 Chainlink, 1H은 Binance)
            double openPrice;
            if ("5M".equals(timeframe)) {
                // ⭐ V7: 5M은 Chainlink 시초가 우선 (폴리마켓 판정 기준)
                openPrice = chainlinkPriceService.get5mOpen(coin);
                if (openPrice <= 0) {
                    openPrice = indicators.getCoin5mOpen(); // Binance fallback
                    log.warn("⚠️ [{}] Chainlink 5M open 없음 → Binance fallback: {}", coin, openPrice);
                }
                if (openPrice <= 0) openPrice = indicators.getCoinHourOpen();
            } else if (is15m) {
                // ⭐ V7: 15M은 Chainlink 시초가 우선 (폴리마켓 판정 기준)
                openPrice = chainlinkPriceService.get15mOpen(coin);
                if (openPrice <= 0) {
                    try {
                        openPrice = marketDataService.fetchCurrent15mOpen(symbol); // Binance fallback
                        log.warn("⚠️ [{}] Chainlink 15M open 없음 → Binance fallback: {}", coin, openPrice);
                    } catch (Exception e) {
                        openPrice = indicators.getCoinHourOpen();
                    }
                }
            } else {
                openPrice = indicators.getCoinHourOpen();
            }

            if (openPrice <= 0) {
                broadcast(String.format("⚠️ [%s] 시초가 조회 실패", coin));
                return false;
            }

            // 3. 방향 & 변동폭 계산
            double pricePct = ((currentPrice - openPrice) / openPrice) * 100;
            String direction = pricePct >= 0 ? "UP" : "DOWN";
            double absPct = Math.abs(pricePct);

            // 캔들 경과 시간
            long now = System.currentTimeMillis();
            int elapsedMin, totalMin;
            if (is15m) {
                long windowStart = (now / 900_000) * 900_000;
                elapsedMin = (int)((now - windowStart) / 60_000);
                totalMin = 15;
            } else {
                long hourStart = (now / 3_600_000) * 3_600_000;
                elapsedMin = (int)((now - hourStart) / 60_000);
                totalMin = 60;
            }
            int remainMin = totalMin - elapsedMin;

            broadcast(String.format("📍 [%s] 시초가 %s → 현재 %s | %s %+.3f%% | %d분 경과, %d분 남음",
                    coin, PriceFormatter.formatWithSymbol(coin, openPrice), PriceFormatter.formatWithSymbol(coin, currentPrice), direction, pricePct, elapsedMin, remainMin));

            // 4. 최소 변동폭 필터 (노이즈 제거)
            double minMovePct = is15m ? 0.15 : 0.25;
            if (absPct < minMovePct) {
                String holdReason = String.format("HOLD - 변동폭 부족 (%.3f%% < %.2f%%)", absPct, minMovePct);
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveMomentumHoldTrade(indicators, coin, timeframe, openPrice, holdReason);
                return false;
            }

            // 5. 폴리마켓 오즈 조회 (CoinConfig 기반 범용)
            PolymarketOddsService.MarketOdds odds;
            if (is15m) {
                odds = oddsService.getOdds15mForCoin(coin);
            } else {
                odds = oddsService.getOddsForCoin(coin);
            }

            broadcast(String.format("📊 [%s] 오즈 - Up: %.0f%% / Down: %.0f%%",
                    coin, odds.upOdds() * 100, odds.downOdds() * 100));

            // 6. Claude 반전 체크 (거부권만)
            broadcast(String.format("🧠 [%s] 반전 위험 체크 중...", coin));
            ClaudeDecisionEngine.ReversalCheck reversal = claudeEngine.checkReversal(
                    indicators, coin, timeframe, direction, pricePct, elapsedMin, remainMin, odds);

            broadcast(String.format("💡 [%s] 반전체크: %s (위험도: %d%%) - %s",
                    coin, reversal.shouldProceed() ? "PROCEED ✅" : "VETO ❌",
                    reversal.reversalRisk(), reversal.reason()));

            if (!reversal.shouldProceed()) {
                String holdReason = String.format("HOLD - Claude VETO (반전위험 %d%%): %s",
                        reversal.reversalRisk(), reversal.reason());
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveMomentumHoldTrade(indicators, coin, timeframe, openPrice, holdReason);
                return false;
            }

            // 7. 잔액 확인
            double balance = balanceService.getBalance();
            if (balance < 1.0) {
                broadcast("🚨 잔액 부족: $" + balance);
                return false;
            }

            // 8. EV 계산 (모멘텀 승률 기반 — Claude confidence 안 씀)
            double momentumWinRate = getMomentumWinRate(coin, timeframe);
            // 반전 위험도로 승률 약간 조정
            double adjustedWinRate = momentumWinRate * (1.0 - reversal.reversalRisk() / 200.0);
            adjustedWinRate = Math.max(adjustedWinRate, 0.40);

            double marketOdds = "UP".equals(direction) ? odds.upOdds() : odds.downOdds();
            ExpectedValueCalculator.EvResult evResult = evCalculator.calculateMomentum(
                    adjustedWinRate, marketOdds, direction);

            broadcast(String.format("📈 [%s] 모멘텀 EV: %+.1f%% (승률 %.0f%%, 오즈 %.0f%%) 임계값 %.0f%%",
                    coin, evResult.bestEv() * 100, adjustedWinRate * 100,
                    marketOdds * 100, evResult.threshold() * 100));

            // 9. EV 필터
            if (minEvThreshold > 0 && evResult.bestEv() < minEvThreshold) {
                String holdReason = String.format("HOLD - EV %.1f%% < 임계값 %.0f%%",
                        evResult.bestEv() * 100, minEvThreshold * 100);
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveMomentumHoldTrade(indicators, coin, timeframe, openPrice, holdReason);
                return false;
            }

            if ("HOLD".equals(evResult.bestAction())) {
                String holdReason = "HOLD - " + evResult.reason();
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveMomentumHoldTrade(indicators, coin, timeframe, openPrice, holdReason);
                return false;
            }

            // 10. 배팅 금액 (Kelly)
            double betAmount = evCalculator.calcBetSize(balance, evResult.bestEv(), marketOdds);
            betAmount = Math.max(betAmount, 1.0);

            Trade.TradeAction finalAction = "UP".equals(direction)
                    ? Trade.TradeAction.BUY_YES
                    : Trade.TradeAction.BUY_NO;

            // 11. 실행
            String dir = finalAction == Trade.TradeAction.BUY_YES ? "UP ⬆️" : "DOWN ⬇️";
            if (dryRun) {
                broadcast(String.format("🟡 [DRY-RUN][%s] 모멘텀 %s | $%.2f | EV: %+.1f%% | 변동: %+.3f%%",
                        coin, dir, betAmount, evResult.bestEv() * 100, pricePct));
            } else {
                broadcast(String.format("🟢 [실제배팅][%s] 모멘텀 %s | $%.2f | EV: %+.1f%%",
                        coin, dir, betAmount, evResult.bestEv() * 100));
                try {
                    String tokenId = getTokenId(odds, finalAction);
                    String orderId = orderService.placeOrder(tokenId, "BUY", betAmount);
                    broadcast(String.format("✅ 주문 성공: %s", orderId));
                } catch (Exception e) {
                    broadcast("❌ 주문 실패: " + e.getMessage());
                    log.error("주문 실패", e);
                }
            }

            // 12. 저장
            TradeDecision decision = TradeDecision.builder()
                    .action(finalAction)
                    .confidence((int)(adjustedWinRate * 100))
                    .amount(betAmount)
                    .reason(String.format("모멘텀 %s %+.3f%% | 반전위험 %d%% | %s",
                            direction, pricePct, reversal.reversalRisk(), reversal.reason()))
                    .rawResponse(String.format("모멘텀 전략 | 방향: %s | 변동: %+.3f%% | 경과: %d분 | 반전체크: %s",
                            direction, pricePct, elapsedMin, reversal.reason()))
                    .marketId(coin.toLowerCase() + "-" + timeframe.toLowerCase() + "-updown")
                    .marketTitle(coin + " Up or Down - " + timeframe)
                    .coin(coin)
                    .timeframe(timeframe)
                    .build();

            Trade trade = saveTrade(decision, indicators, odds, evResult, betAmount, coin, timeframe);
            balanceService.deductBet(betAmount);
            botStateService.recordCycle(coin + " 모멘텀 " + dir + " $" + String.format("%.2f", betAmount));
            broadcast(String.format("✅ [%s] 저장 완료 (ID: %d) | 잔액: $%.2f", coin, trade.getId(), balanceService.getBalance()));
            return true;

        } catch (Exception e) {
            log.error("[{}] 모멘텀 사이클 오류: {}", coin, e.getMessage(), e);
            broadcast(String.format("❌ [%s] 오류: %s", coin, e.getMessage()));
            return false;
        }
    }

    /**
     * 모멘텀 전략 과거 승률 조회
     * 캔들 후반부 진입 + 방향 추종 시의 실제 승률
     */
    private double getMomentumWinRate(String coin, String timeframe) {
        var recent = tradeRepository.findTop50ByCoinAndTimeframeForStats(coin, timeframe);
        long resolved = recent.stream()
                .filter(t -> t.getResult() == Trade.TradeResult.WIN || t.getResult() == Trade.TradeResult.LOSE)
                .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                .count();
        if (resolved < 5) return 0.62; // 기본값: 모멘텀 추종 경험적 승률
        long wins = recent.stream()
                .filter(t -> t.getResult() == Trade.TradeResult.WIN)
                .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                .count();
        return (double) wins / resolved;
    }

    /**
     * 오즈 지연 트레이드 저장 + 잔액 차감 (OddsLagDetector에서 호출)
     */
    public void saveAndDeductLagTrade(TradeDecision decision, MarketIndicators indicators,
                                       PolymarketOddsService.MarketOdds odds,
                                       ExpectedValueCalculator.EvResult evResult,
                                       double betAmount, String coin, String timeframe) {
        Trade trade = saveTrade(decision, indicators, odds, evResult, betAmount, coin, timeframe);
        balanceService.deductBet(betAmount);
        botStateService.recordCycle(coin + " ⚡오즈지연 " +
                (decision.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN") +
                " $" + String.format("%.2f", betAmount));
        broadcast(String.format("✅ [%s] 오즈지연 저장 (ID: %d) | 잔액: $%.2f",
                coin, trade.getId(), balanceService.getBalance()));
    }

    private void saveMomentumHoldTrade(MarketIndicators indicators, String coin, String timeframe,
                                        double openPrice, String holdReason) {
        double entryPrice = indicators.getCoinPrice();
        Trade trade = Trade.builder()
                .coin(coin)
                .timeframe(timeframe)
                .marketId(coin.toLowerCase() + "-" + timeframe.toLowerCase() + "-updown")
                .marketTitle(coin + " Up or Down - " + timeframe)
                .action(Trade.TradeAction.HOLD)
                .betAmount(0.0)
                .entryPrice(entryPrice)
                .openPrice(openPrice)
                .confidence(0)
                .reason(holdReason)
                .claudeAnalysis("모멘텀 전략 HOLD")
                .fundingRate(indicators.getFundingRate())
                .openInterestChange("15M".equals(timeframe) ? indicators.getOpenInterestChange5m() : indicators.getOpenInterestChange())
                .buyOdds(0.0)
                .btcChange1h(indicators.getBtcChange1h())
                .ethChange1h(indicators.getEthChange1h())
                .ethChange4h(indicators.getEthChange4h())
                .ethChange24h(indicators.getEthChange24h())
                .fearGreedIndex(indicators.getFearGreedIndex())
                .marketTrend(indicators.getTrend())
                .result(Trade.TradeResult.HOLD)
                .profitLoss(0.0)
                .build();
        tradeRepository.save(trade);
    }

    /**
     * 1H 사이클 실행 (기본) - 기존 전략 (레거시, 15M에서 사용)
     */
    public boolean executeCycle(String coin) {
        return executeCycle(coin, "1H", -1);
    }

    /**
     * 1H 사이클 실행 (EV 임계값 지정)
     */
    public boolean executeCycle(String coin, double minEvThreshold) {
        return executeCycle(coin, "1H", minEvThreshold);
    }

    /**
     * BTC 또는 ETH 사이클 실행 (타임프레임 + EV 임계값)
     * @param timeframe "1H" or "15M"
     * @param minEvThreshold 최소 EV 임계값 (0.15 = 15%). -1이면 기본 동적 임계값 사용
     * @return 실제 배팅이 실행되었으면 true
     */
    public boolean executeCycle(String coin, String timeframe, double minEvThreshold) {
        String tfLabel = timeframe;
        broadcast(String.format("🔄 [%s %s] 분석 시작...", coin, tfLabel));
        try {
            // 1. 시장 지표 수집
            broadcast(String.format("📡 [%s %s] 바이낸스 데이터 수집 중...", coin, tfLabel));
            MarketIndicators indicators = marketDataService.collect(coin);

            broadcast(String.format("💹 [%s] 현재가: %s | 1H: %+.2f%% | 펀딩비: %+.4f%% | 공포탐욕: %d(%s)",
                    coin,
                    PriceFormatter.formatWithSymbol(coin, indicators.getCoinPrice()),
                    indicators.getCoinChange1h(),
                    indicators.getFundingRate(),
                    indicators.getFearGreedIndex(),
                    indicators.getFearGreedLabel()));

            // 2. 폴리마켓 실시간 오즈 조회
            broadcast(String.format("🎯 [%s %s] 폴리마켓 오즈 조회 중...", coin, tfLabel));
            PolymarketOddsService.MarketOdds odds;
            if ("15M".equals(timeframe)) {
                odds = oddsService.getOdds15mForCoin(coin);
            } else {
                odds = oddsService.getOddsForCoin(coin);
            }

            broadcast(String.format("📊 [%s] 오즈 - Up: %.0f%% / Down: %.0f%%",
                    coin, odds.upOdds() * 100, odds.downOdds() * 100));

            // 3. 잔액 확인
            double balance = balanceService.getBalance();
            if (balance < 1.0) {
                broadcast("🚨 잔액 부족: $" + balance);
                return false;
            }

            // 4. Claude 판단 (coin별 독립 프롬프트 + 타임프레임 + 오즈)
            broadcast(String.format("🧠 [%s %s] Claude 분석 중...", coin, tfLabel));
            TradeDecision decision = claudeEngine.decide(indicators, balance, coin, timeframe, odds);
            broadcast(String.format("💡 [%s] Claude 판단: %s (확신도: %d%%) - %s",
                    coin, decision.getAction(), decision.getConfidence(), decision.getReason()));

            // 5. 기댓값 계산
            double claudeUpProb = decision.getConfidence() / 100.0;
            if (decision.getAction() == Trade.TradeAction.BUY_NO) {
                claudeUpProb = 1.0 - claudeUpProb; // DOWN 판단이면 Up 확률 반전
            } else if (decision.getAction() == Trade.TradeAction.HOLD) {
                claudeUpProb = 0.5; // HOLD면 50%
            }

            double recentWinRate = getRecentWinRate(coin);
            ExpectedValueCalculator.EvResult evResult = evCalculator.calculate(
                    claudeUpProb, odds.upOdds(), recentWinRate);

            broadcast(String.format("📈 [%s] 기댓값 - Up: %+.1f%% / Down: %+.1f%% / 임계값: %.0f%%",
                    coin, evResult.upEv() * 100, evResult.downEv() * 100, evResult.threshold() * 100));

            // 5-1. 스케줄러 지정 최소 EV 임계값 적용
            if (minEvThreshold > 0 && evResult.bestEv() < minEvThreshold) {
                String holdReason = String.format("HOLD - EV %.1f%% < 트리거 임계값 %.0f%%",
                        evResult.bestEv() * 100, minEvThreshold * 100);
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveHoldTrade(decision, indicators, odds, evResult, coin, timeframe, holdReason);
                botStateService.recordCycle(coin + " HOLD (EV부족)");
                return false;
            }

            // 6. 최종 결정: EV 기반
            if ("HOLD".equals(evResult.bestAction()) || decision.getAction() == Trade.TradeAction.HOLD) {
                String holdReason = "HOLD - " + evResult.reason();
                broadcast(String.format("⏸️ [%s] %s", coin, holdReason));
                saveHoldTrade(decision, indicators, odds, evResult, coin, timeframe, holdReason);
                botStateService.recordCycle(coin + " HOLD");
                return false;
            }

            // 7. Kelly 기반 배팅 금액
            double marketOdds = "UP".equals(evResult.bestAction()) ? odds.upOdds() : odds.downOdds();
            double betAmount = evCalculator.calcBetSize(balance, evResult.bestEv(), marketOdds);
            betAmount = Math.max(betAmount, 1.0);

            Trade.TradeAction finalAction = "UP".equals(evResult.bestAction())
                    ? Trade.TradeAction.BUY_YES
                    : Trade.TradeAction.BUY_NO;

            // 8. 실행
            String dir = finalAction == Trade.TradeAction.BUY_YES ? "UP ⬆️" : "DOWN ⬇️";
            if (dryRun) {
                broadcast(String.format("🟡 [DRY-RUN][%s] %s | $%.2f | EV: %+.1f%%",
                        coin, dir, betAmount, evResult.bestEv() * 100));
            } else {
                broadcast(String.format("🟢 [실제배팅][%s] %s | $%.2f | EV: %+.1f%%",
                        coin, dir, betAmount, evResult.bestEv() * 100));
                try {
                    // 폴리마켓 실제 주문
                    String tokenId = getTokenId(odds, finalAction);
                    String orderId = orderService.placeOrder(tokenId, "BUY", betAmount);
                    broadcast(String.format("✅ 주문 성공: %s", orderId));
                } catch (Exception e) {
                    broadcast("❌ 주문 실패: " + e.getMessage());
                    log.error("주문 실패", e);
                }
            }

            decision.setAction(finalAction);
            Trade trade = saveTrade(decision, indicators, odds, evResult, betAmount, coin, timeframe);
            balanceService.deductBet(betAmount);
            botStateService.recordCycle(coin + " " + dir + " $" + String.format("%.2f", betAmount)
                    + " (잔액 $" + String.format("%.2f", balanceService.getBalance()) + ")");
            broadcast(String.format("✅ [%s] 저장 완료 (ID: %d) | 잔액: $%.2f", coin, trade.getId(), balanceService.getBalance()));
            return true;

        } catch (Exception e) {
            log.error("[{}] 사이클 오류: {}", coin, e.getMessage(), e);
            broadcast(String.format("❌ [%s] 오류: %s", coin, e.getMessage()));
            return false;
        }
    }

    private Trade saveTrade(TradeDecision decision, MarketIndicators indicators,
                             PolymarketOddsService.MarketOdds odds,
                             ExpectedValueCalculator.EvResult evResult,
                             double betAmount, String coin, String timeframe) {
        double entryPrice = indicators.getCoinPrice();
        // 시초가: 5M/15M은 Chainlink 우선 (폴리마켓 판정 기준), 1H은 Binance
        double openPrice;
        if ("5M".equals(timeframe)) {
            openPrice = chainlinkPriceService.get5mOpen(coin);
            if (openPrice <= 0) openPrice = indicators.getCoin5mOpen();
            if (openPrice <= 0) openPrice = indicators.getCoinHourOpen();
        } else if ("15M".equals(timeframe)) {
            openPrice = chainlinkPriceService.get15mOpen(coin);
            if (openPrice <= 0) {
                try {
                    openPrice = marketDataService.fetchCurrent15mOpen(coin + "USDT");
                } catch (Exception e) {
                    log.warn("15M 시초가 조회 실패, fallback 사용", e);
                    openPrice = indicators.getCoin15mOpen() > 0 ? indicators.getCoin15mOpen() : indicators.getCoinHourOpen();
                }
            }
        } else {
            openPrice = indicators.getCoinHourOpen();
        }
        Trade trade = Trade.builder()
                .coin(coin)
                .timeframe(timeframe)
                .marketId(odds.marketId())
                .marketTitle(coin + " Up or Down - " + timeframe)
                .action(decision.getAction())
                .betAmount(betAmount)
                .entryPrice(entryPrice)
                .openPrice(openPrice > 0 ? openPrice : null)
                .confidence(decision.getConfidence())
                .reason(decision.getReason() + " | EV: " + String.format("%+.1f%%", evResult.bestEv() * 100))
                .claudeAnalysis(decision.getRawResponse())
                // 지표 저장
                .fundingRate(indicators.getFundingRate())
                .openInterestChange("15M".equals(timeframe) ? indicators.getOpenInterestChange5m() : indicators.getOpenInterestChange())
                .buyOdds(decision.getAction() == Trade.TradeAction.BUY_YES ? odds.upOdds() : odds.downOdds())
                .btcChange1h(indicators.getBtcChange1h())
                .ethChange1h(indicators.getEthChange1h())
                .ethChange4h(indicators.getEthChange4h())
                .ethChange24h(indicators.getEthChange24h())
                .fearGreedIndex(indicators.getFearGreedIndex())
                .marketTrend(indicators.getTrend())
                .result(Trade.TradeResult.PENDING)
                .build();
        return tradeRepository.save(trade);
    }

    private void saveHoldTrade(TradeDecision decision, MarketIndicators indicators,
                               PolymarketOddsService.MarketOdds odds,
                               ExpectedValueCalculator.EvResult evResult,
                               String coin, String timeframe, String holdReason) {
        double entryPrice = indicators.getCoinPrice();
        double openPrice;
        if ("5M".equals(timeframe)) {
            openPrice = chainlinkPriceService.get5mOpen(coin);
            if (openPrice <= 0) openPrice = indicators.getCoin5mOpen();
            if (openPrice <= 0) openPrice = indicators.getCoinHourOpen();
        } else if ("15M".equals(timeframe)) {
            openPrice = chainlinkPriceService.get15mOpen(coin);
            if (openPrice <= 0) {
                try {
                    openPrice = marketDataService.fetchCurrent15mOpen(coin + "USDT");
                } catch (Exception e) {
                    openPrice = indicators.getCoin15mOpen() > 0 ? indicators.getCoin15mOpen() : indicators.getCoinHourOpen();
                }
            }
        } else {
            openPrice = indicators.getCoinHourOpen();
        }
        Trade trade = Trade.builder()
                .coin(coin)
                .timeframe(timeframe)
                .marketId(odds.marketId())
                .marketTitle(coin + " Up or Down - " + timeframe)
                .action(Trade.TradeAction.HOLD)
                .betAmount(0.0)
                .entryPrice(entryPrice)
                .openPrice(openPrice > 0 ? openPrice : null)
                .confidence(decision.getConfidence())
                .reason(holdReason + " | EV: " + String.format("%+.1f%%", evResult.bestEv() * 100))
                .claudeAnalysis(decision.getRawResponse())
                .fundingRate(indicators.getFundingRate())
                .openInterestChange("15M".equals(timeframe) ? indicators.getOpenInterestChange5m() : indicators.getOpenInterestChange())
                .buyOdds(0.0)
                .btcChange1h(indicators.getBtcChange1h())
                .ethChange1h(indicators.getEthChange1h())
                .ethChange4h(indicators.getEthChange4h())
                .ethChange24h(indicators.getEthChange24h())
                .fearGreedIndex(indicators.getFearGreedIndex())
                .marketTrend(indicators.getTrend())
                .result(Trade.TradeResult.HOLD)
                .profitLoss(0.0)
                .build();
        tradeRepository.save(trade);
    }

    public void updateTradeResult(Long tradeId, Trade.TradeResult result, Double exitPrice) {
        tradeRepository.findById(tradeId).ifPresent(trade -> {
            trade.setResult(result);
            trade.setExitPrice(exitPrice);
            trade.setResolvedAt(java.time.LocalDateTime.now());

            // 폴리마켓 실제 PNL 계산
            // WIN: shares = betAmount / buyOdds → payout = shares × $1 → profit = (payout - cost) × 0.98
            // LOSE: -betAmount (전액 손실)
            double pnl;
            if (result == Trade.TradeResult.WIN) {
                double odds = (trade.getBuyOdds() != null && trade.getBuyOdds() > 0)
                        ? trade.getBuyOdds() : 0.5; // 레거시 fallback
                double shares = trade.getBetAmount() / odds;     // 매수한 주 수
                double payout = shares;                           // WIN 시 주당 $1
                double grossProfit = payout - trade.getBetAmount(); // 총 이익
                pnl = grossProfit * 0.98;                         // 2% 수수료 차감
            } else {
                pnl = -trade.getBetAmount();
            }
            trade.setProfitLoss(pnl);
            tradeRepository.save(trade);

            // 잔액 반영
            balanceService.onTradeResult(trade);

            reflectionService.reflect(trade);
            broadcast(String.format("📊 Trade #%d [%s] 결과: %s | PNL: $%.2f | 잔액: $%.2f",
                    tradeId, trade.getCoin(), result, pnl, balanceService.getBalance()));
        });
    }

    private double getRecentWinRate(String coin) {
        var recent = tradeRepository.findTop20ByCoinOrderByCreatedAtDesc(coin);
        long resolved = recent.stream()
                .filter(t -> t.getResult() == Trade.TradeResult.WIN || t.getResult() == Trade.TradeResult.LOSE)
                .count();
        if (resolved == 0) return 0;
        long wins = recent.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        return (double) wins / resolved;
    }

    public void broadcast(String message) {
        log.info(message);
        try {
            messagingTemplate.convertAndSend("/topic/trading", message);
        } catch (Exception e) {
            log.warn("WebSocket 전송 실패: {}", e.getMessage());
        }
    }

    private String getTokenId(PolymarketOddsService.MarketOdds odds, Trade.TradeAction action) {
        if (action == Trade.TradeAction.BUY_YES) {
            return odds.yesTokenId() != null ? odds.yesTokenId() : odds.marketId() + "-yes";
        } else {
            return odds.noTokenId() != null ? odds.noTokenId() : odds.marketId() + "-no";
        }
    }
}
