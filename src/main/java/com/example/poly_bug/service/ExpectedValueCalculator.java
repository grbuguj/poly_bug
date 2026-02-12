package com.example.poly_bug.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 폴리마켓 배팅 기댓값 계산
 * 기댓값 = (내 판단 확률 / 시장 오즈) - 1
 */
@Slf4j
@Service
public class ExpectedValueCalculator {

    // 기본 임계값 (15% 이상일 때 배팅)
    private static final double DEFAULT_THRESHOLD = 0.15;

    // 동적 임계값 조정 범위
    private static final double MIN_THRESHOLD = 0.08;
    private static final double MAX_THRESHOLD = 0.25;

    public record EvResult(
            double upEv,           // Up 배팅 기댓값
            double downEv,         // Down 배팅 기댓값
            String bestAction,     // "UP", "DOWN", "HOLD"
            double bestEv,         // 최선의 기댓값
            double threshold,      // 적용된 임계값
            String reason          // 판단 근거
    ) {}

    /**
     * 기댓값 계산 및 배팅 여부 결정
     *
     * @param claudeUpProb   Claude가 판단한 Up 확률 (0~1)
     * @param marketUpOdds   폴리마켓 현재 Up 오즈 (0~1)
     * @param recentWinRate  최근 승률 (동적 임계값 조정용, 0~1)
     */
    public EvResult calculate(double claudeUpProb, double marketUpOdds, double recentWinRate) {
        // 이상값 방어
        claudeUpProb = clamp(claudeUpProb, 0.01, 0.99);
        marketUpOdds = clamp(marketUpOdds, 0.01, 0.99);

        double claudeDownProb = 1.0 - claudeUpProb;
        double marketDownOdds = 1.0 - marketUpOdds;

        // 기댓값 계산
        // Up 배팅: 이기면 (1/marketUpOdds - 1) 수익, 지면 -1
        double upEv = (claudeUpProb / marketUpOdds) - 1.0;

        // Down 배팅: 이기면 (1/marketDownOdds - 1) 수익, 지면 -1
        double downEv = (claudeDownProb / marketDownOdds) - 1.0;

        // 동적 임계값 (최근 승률 기반 조정)
        double threshold = calcDynamicThreshold(recentWinRate);

        // 배팅 결정
        String bestAction;
        double bestEv;
        String reason;

        if (upEv > downEv && upEv > threshold) {
            bestAction = "UP";
            bestEv = upEv;
            reason = String.format(
                "Up EV: +%.1f%% (내 확률 %.0f%% vs 오즈 %.0f%%) > 임계값 %.0f%%",
                upEv * 100, claudeUpProb * 100, marketUpOdds * 100, threshold * 100
            );
        } else if (downEv > upEv && downEv > threshold) {
            bestAction = "DOWN";
            bestEv = downEv;
            reason = String.format(
                "Down EV: +%.1f%% (내 확률 %.0f%% vs 오즈 %.0f%%) > 임계값 %.0f%%",
                downEv * 100, claudeDownProb * 100, marketDownOdds * 100, threshold * 100
            );
        } else {
            bestAction = "HOLD";
            bestEv = Math.max(upEv, downEv);
            reason = String.format(
                "기댓값 부족 - Up EV: %+.1f%%, Down EV: %+.1f%% (임계값: %.0f%%)",
                upEv * 100, downEv * 100, threshold * 100
            );
        }

        log.info("[EV] {} - upEV: {:.2f}% / downEV: {:.2f}% / 임계값: {:.0f}% → {}",
                bestAction, upEv * 100, downEv * 100, threshold * 100, bestAction);

        return new EvResult(upEv, downEv, bestAction, bestEv, threshold, reason);
    }

    /**
     * 동적 임계값 조정
     * 최근 승률이 높으면 임계값 낮춰서 더 많이 배팅
     * 최근 승률이 낮으면 임계값 높여서 신중하게
     */
    private double calcDynamicThreshold(double recentWinRate) {
        if (recentWinRate <= 0) return DEFAULT_THRESHOLD; // 데이터 없으면 기본값

        if (recentWinRate >= 0.65) {
            // 승률 65% 이상 → 임계값 낮춤 (더 많이 배팅)
            return MIN_THRESHOLD;
        } else if (recentWinRate >= 0.55) {
            // 승률 55~65% → 기본값
            return DEFAULT_THRESHOLD;
        } else {
            // 승률 55% 미만 → 임계값 높임 (신중하게)
            return MAX_THRESHOLD;
        }
    }

    /**
     * 배팅 금액 계산 (Kelly Criterion 단순화 버전)
     * f = (EV) / (배당 - 1)
     */
    public double calcBetSize(double balance, double ev, double marketOdds) {
        if (ev <= 0) return 0;
        double payout = 1.0 / marketOdds; // 배당
        double kellyFraction = ev / (payout - 1.0);
        // Kelly의 25%만 사용 (리스크 관리)
        double safeFraction = kellyFraction * 0.25;
        // 최소 2%, 최대 10%
        safeFraction = clamp(safeFraction, 0.02, 0.10);
        return balance * safeFraction;
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
