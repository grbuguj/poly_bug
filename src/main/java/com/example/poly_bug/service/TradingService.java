package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
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

    @Value("${trading.dry-run}")
    private boolean dryRun;

    @Value("${trading.balance:100.0}")
    private double mockBalance;

    /**
     * BTC 또는 ETH 1H 사이클 실행
     */
    public void executeCycle(String coin) {
        broadcast(String.format("🔄 [%s 1H] 분석 시작...", coin));
        try {
            // 1. 시장 지표 수집
            broadcast(String.format("📡 [%s] 바이낸스 데이터 수집 중...", coin));
            MarketIndicators indicators = marketDataService.collect(coin);

            broadcast(String.format("💹 [%s] 현재가: $%.2f | 1H: %+.2f%% | 펀딩비: %+.4f%% | 공포탐욕: %d(%s)",
                    coin,
                    "BTC".equals(coin) ? indicators.getBtcPrice() : indicators.getEthPrice(),
                    indicators.getEthChange1h(),
                    indicators.getFundingRate(),
                    indicators.getFearGreedIndex(),
                    indicators.getFearGreedLabel()));

            // 2. 폴리마켓 실시간 오즈 조회
            broadcast(String.format("🎯 [%s] 폴리마켓 오즈 조회 중...", coin));
            PolymarketOddsService.MarketOdds odds = "BTC".equals(coin)
                    ? oddsService.getBtcOdds()
                    : oddsService.getEthOdds();

            broadcast(String.format("📊 [%s] 오즈 - Up: %.0f%% / Down: %.0f%%",
                    coin, odds.upOdds() * 100, odds.downOdds() * 100));

            // 3. 잔액 확인
            double balance = dryRun ? mockBalance : getBalance();
            if (balance < 1.0) {
                broadcast("🚨 잔액 부족: $" + balance);
                return;
            }

            // 4. Claude 판단 (coin별 독립 프롬프트)
            broadcast(String.format("🧠 [%s] Claude 분석 중...", coin));
            TradeDecision decision = claudeEngine.decide(indicators, balance, coin);
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

            // 6. 최종 결정: EV 기반
            if ("HOLD".equals(evResult.bestAction()) || decision.getAction() == Trade.TradeAction.HOLD) {
                broadcast(String.format("⏸️ [%s] HOLD - %s", coin, evResult.reason()));
                saveTrade(decision, indicators, odds, evResult, 0.0, coin);
                botStateService.recordCycle(coin + " HOLD");
                return;
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
                // TODO: Python 서버로 실제 주문 전달
            }

            decision.setAction(finalAction);
            Trade trade = saveTrade(decision, indicators, odds, evResult, betAmount, coin);
            botStateService.recordCycle(coin + " " + dir + " $" + String.format("%.2f", betAmount));
            broadcast(String.format("✅ [%s] 저장 완료 (ID: %d)", coin, trade.getId()));

        } catch (Exception e) {
            log.error("[{}] 사이클 오류: {}", coin, e.getMessage(), e);
            broadcast(String.format("❌ [%s] 오류: %s", coin, e.getMessage()));
        }
    }

    private Trade saveTrade(TradeDecision decision, MarketIndicators indicators,
                             PolymarketOddsService.MarketOdds odds,
                             ExpectedValueCalculator.EvResult evResult,
                             double betAmount, String coin) {
        double entryPrice = "BTC".equals(coin) ? indicators.getBtcPrice() : indicators.getEthPrice();
        Trade trade = Trade.builder()
                .coin(coin)
                .timeframe("1H")
                .marketId(odds.marketId())
                .marketTitle(coin + " Up or Down - 1 Hour")
                .action(decision.getAction())
                .betAmount(betAmount)
                .entryPrice(entryPrice)
                .confidence(decision.getConfidence())
                .reason(decision.getReason() + " | EV: " + String.format("%+.1f%%", evResult.bestEv() * 100))
                // 지표 저장
                .fundingRate(indicators.getFundingRate())
                .openInterestChange(indicators.getOpenInterestChange())
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

    public void updateTradeResult(Long tradeId, Trade.TradeResult result, Double exitPrice) {
        tradeRepository.findById(tradeId).ifPresent(trade -> {
            trade.setResult(result);
            trade.setExitPrice(exitPrice);
            double pnl = result == Trade.TradeResult.WIN
                    ? trade.getBetAmount() * 0.9
                    : -trade.getBetAmount();
            trade.setProfitLoss(pnl);
            tradeRepository.save(trade);
            reflectionService.reflect(trade);
            broadcast(String.format("📊 Trade #%d [%s] 결과: %s | PNL: $%.2f",
                    tradeId, trade.getCoin(), result, pnl));
        });
    }

    private double getRecentWinRate(String coin) {
        var recent = tradeRepository.findTop20ByCoinOrderByCreatedAtDesc(coin);
        long resolved = recent.stream().filter(t -> t.getResult() != Trade.TradeResult.PENDING).count();
        if (resolved == 0) return 0;
        long wins = recent.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        return (double) wins / resolved;
    }

    private double getBalance() {
        return mockBalance; // TODO: 폴리마켓 잔액 API 연동
    }

    public void broadcast(String message) {
        log.info(message);
        try {
            messagingTemplate.convertAndSend("/topic/trading", message);
        } catch (Exception e) {
            log.warn("WebSocket 전송 실패: {}", e.getMessage());
        }
    }
}
