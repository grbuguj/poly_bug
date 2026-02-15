package com.example.poly_bug.service;

import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가상 잔액 추적 (DRY-RUN 전용)
 *
 * 초기 자금에서 배팅/승패에 따라 실시간 반영
 * - 배팅 시: 잔액 -= betAmount
 * - WIN 시:  잔액 += betAmount / odds  (폴리마켓 페이아웃 = $1/share)
 * - LOSE 시: 이미 차감됨 (추가 없음)
 *
 * 서버 재시작 시 DB에서 복원
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final TradeRepository tradeRepository;

    @Value("${trading.initial-balance:50.0}")
    private double initialBalance;

    private final AtomicReference<Double> balance = new AtomicReference<>(0.0);

    @PostConstruct
    public void init() {
        recalcFromDb();
    }

    /**
     * DB 기록에서 잔액 재계산 (서버 재시작 시)
     */
    public void recalcFromDb() {
        double bal = initialBalance;

        List<Trade> allTrades = tradeRepository.findAll();
        for (Trade t : allTrades) {
            if (t.getAction() == Trade.TradeAction.HOLD) continue;
            if (t.getBetAmount() == null || t.getBetAmount() <= 0) continue;

            // 배팅 차감
            bal -= t.getBetAmount();

            // 결과 반영
            if (t.getResult() == Trade.TradeResult.WIN) {
                // 원금 회수 + 순이익 (profitLoss = 오즈 기반 이익 - 2% 수수료)
                double pnl;
                if (t.getProfitLoss() != null) {
                    pnl = t.getProfitLoss();
                } else {
                    // 레거시: buyOdds로 계산, 없으면 50% 가정
                    double odds = (t.getBuyOdds() != null && t.getBuyOdds() > 0) ? t.getBuyOdds() : 0.5;
                    double grossProfit = (t.getBetAmount() / odds) - t.getBetAmount();
                    pnl = grossProfit * 0.98;
                }
                bal += t.getBetAmount() + pnl;
            }
            // LOSE: 이미 차감됨
            // PENDING: 차감만 된 상태 (아직 결과 모름)
        }

        balance.set(bal);
        log.info("💰 잔액 복원: ${} (초기 ${}, 배팅 {}건)", String.format("%.2f", bal), initialBalance, allTrades.size());
    }

    /** 현재 잔액 */
    public double getBalance() {
        return balance.get();
    }

    /** 초기 자금 */
    public double getInitialBalance() {
        return initialBalance;
    }

    /** 배팅 시 차감 */
    public void deductBet(double amount) {
        balance.updateAndGet(b -> b - amount);
        log.info("💸 배팅 차감 -${} → 잔액 ${}", String.format("%.2f", amount), String.format("%.2f", balance.get()));
    }

    /** WIN 시 수익 추가 */
    public void addWinnings(double payout) {
        balance.updateAndGet(b -> b + payout);
        log.info("💰 수익 +${} → 잔액 ${}", String.format("%.2f", payout), String.format("%.2f", balance.get()));
    }

    /** PENDING → 결과 확정 시 호출 (LOSE면 이미 차감, WIN이면 수익 추가) */
    public void onTradeResult(Trade trade) {
        if (trade.getResult() == Trade.TradeResult.WIN) {
            // 원금 회수 + 순이익 (profitLoss에 수수료 차감 후 순이익 들어있음)
            double payout = trade.getBetAmount() + trade.getProfitLoss();
            addWinnings(payout);
        }
        // LOSE: 이미 deductBet에서 차감됨
    }

    /** 수익률 */
    public double getProfitPct() {
        return ((balance.get() - initialBalance) / initialBalance) * 100;
    }
}
