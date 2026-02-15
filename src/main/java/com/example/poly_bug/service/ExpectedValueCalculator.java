package com.example.poly_bug.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 폴리마켓 배팅 기댓값 계산 V2
 *
 * 순방향: EV = (추정확률 / 시장오즈) - 1   [오즈 15-85% 클램프]
 * 역방향: EV = (추정확률 / 시장오즈) - 1   [오즈 5-95% 클램프 — 싼 오즈가 핵심]
 *
 * Kelly: EV 크기에 비례하는 동적 배팅 사이즈
 *   - EV 10-30%: 잔액 2-4%
 *   - EV 30-80%: 잔액 4-7%
 *   - EV 80%+:   잔액 7-10%
 */
@Slf4j
@Service
public class ExpectedValueCalculator {

    private static final double DEFAULT_THRESHOLD = 0.15;
    private static final double MIN_THRESHOLD = 0.08;
    private static final double MAX_THRESHOLD = 0.25;

    // === 순방향 오즈 범위 (보수적) ===
    private static final double FWD_MIN_ODDS = 0.20; // V5: 15%→20%
    private static final double FWD_MAX_ODDS = 0.80; // V5: 85%→80%

    // === 역방향 오즈 범위 (공격적 — 싼 오즈가 핵심!) ===
    private static final double REV_MIN_ODDS = 0.05;
    private static final double REV_MAX_ODDS = 0.95;

    private static final double MAX_EV = 0.80; // ⭐ V5: 300%→80% (비현실적 EV 제거)

    public record EvResult(
            double upEv,
            double downEv,
            String bestAction,
            double bestEv,
            double threshold,
            String reason
    ) {}

    /**
     * 기존 전략용 EV (Claude confidence 기반)
     */
    public EvResult calculate(double claudeUpProb, double marketUpOdds, double recentWinRate) {
        claudeUpProb = clamp(claudeUpProb, 0.05, 0.95);
        double rawMarketUp = marketUpOdds;
        marketUpOdds = clamp(marketUpOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);

        double claudeDownProb = 1.0 - claudeUpProb;
        double marketDownOdds = 1.0 - marketUpOdds;

        double upEv = Math.min((claudeUpProb / marketUpOdds) - 1.0, MAX_EV);
        double downEv = Math.min((claudeDownProb / marketDownOdds) - 1.0, MAX_EV);

        double threshold = calcDynamicThreshold(recentWinRate);

        String bestAction;
        double bestEv;
        String reason;

        if (upEv > downEv && upEv > threshold) {
            bestAction = "UP";
            bestEv = upEv;
            reason = String.format("Up EV: +%.1f%% (내 확률 %.0f%% vs 오즈 %.0f%%) > 임계값 %.0f%%",
                    upEv * 100, claudeUpProb * 100, marketUpOdds * 100, threshold * 100);
        } else if (downEv > upEv && downEv > threshold) {
            bestAction = "DOWN";
            bestEv = downEv;
            reason = String.format("Down EV: +%.1f%% (내 확률 %.0f%% vs 오즈 %.0f%%) > 임계값 %.0f%%",
                    downEv * 100, claudeDownProb * 100, marketDownOdds * 100, threshold * 100);
        } else {
            bestAction = "HOLD";
            bestEv = Math.max(upEv, downEv);
            reason = String.format("기댓값 부족 - Up EV: %+.1f%%, Down EV: %+.1f%% (임계값: %.0f%%)",
                    upEv * 100, downEv * 100, threshold * 100);
        }

        log.info("[EV] claudeUp={}% | 원본오즈={}% → 보정오즈={}% | upEv={}% downEv={}% | {} (임계값 {}%)",
                String.format("%.0f", claudeUpProb * 100),
                String.format("%.1f", rawMarketUp * 100),
                String.format("%.0f", marketUpOdds * 100),
                String.format("%+.1f", upEv * 100),
                String.format("%+.1f", downEv * 100),
                bestAction,
                String.format("%.0f", threshold * 100));

        return new EvResult(upEv, downEv, bestAction, bestEv, threshold, reason);
    }

    private double calcDynamicThreshold(double recentWinRate) {
        if (recentWinRate <= 0) return DEFAULT_THRESHOLD;
        if (recentWinRate >= 0.65) return MIN_THRESHOLD;
        if (recentWinRate >= 0.55) return DEFAULT_THRESHOLD;
        return MAX_THRESHOLD;
    }

    /**
     * 순방향 모멘텀 EV — 시장이 아직 반영 안 한 갭
     */
    public EvResult calculateMomentum(double momentumWinRate, double marketOdds, String direction) {
        momentumWinRate = clamp(momentumWinRate, 0.40, 0.90);
        double rawOdds = marketOdds;
        marketOdds = clamp(marketOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);

        double ev = Math.min((momentumWinRate / marketOdds) - 1.0, MAX_EV);
        double threshold = 0.08; // V5: 10%→8% (밤새 0건 수정)

        String bestAction;
        double bestEv;
        String reason;

        if (ev > threshold) {
            bestAction = direction;
            bestEv = ev;
            reason = String.format("순방향 %s EV: +%.1f%% (추정%.0f%% vs 오즈%.0f%%) > 임계값%.0f%%",
                    direction, ev * 100, momentumWinRate * 100, marketOdds * 100, threshold * 100);
        } else {
            bestAction = "HOLD";
            bestEv = ev;
            reason = String.format("순방향 EV부족 - %s EV: %+.1f%% (추정%.0f%% vs 오즈%.0f%%, 임계값%.0f%%)",
                    direction, ev * 100, momentumWinRate * 100, marketOdds * 100, threshold * 100);
        }

        log.info("[EV-순방향] {} | 추정={}% | 원본오즈={}% → 보정오즈={}% | ev={}% | {}",
                direction,
                String.format("%.0f", momentumWinRate * 100),
                String.format("%.1f", rawOdds * 100),
                String.format("%.0f", marketOdds * 100),
                String.format("%+.1f", ev * 100),
                bestAction);

        double upEv = "UP".equals(direction) ? ev : -1;
        double downEv = "DOWN".equals(direction) ? ev : -1;
        return new EvResult(upEv, downEv, bestAction, bestEv, threshold, reason);
    }

    /**
     * ⭐ 역방향 EV — 시장이 과잉반응, 반대쪽이 저평가
     *
     * 핵심: 오즈 클램프를 5%까지 허용 → 11¢짜리 DOWN의 진짜 EV를 정확히 계산
     * 예: DOWN 추정 34%, 시장 11¢ → EV = (34/11)-1 = +209%
     *     기존 클램프(15%): EV = (34/15)-1 = +127% ← 40% 과소평가
     */
    public EvResult calculateReverse(double reverseEstProb, double reverseMarketOdds, String betDirection) {
        reverseEstProb = clamp(reverseEstProb, 0.15, 0.60); // 역방향 확률은 15-60% 범위
        double rawOdds = reverseMarketOdds;
        // ⭐ 핵심: 5%까지 허용 → 싼 오즈의 진짜 가치를 계산
        reverseMarketOdds = clamp(reverseMarketOdds, REV_MIN_ODDS, REV_MAX_ODDS);

        double ev = Math.min((reverseEstProb / reverseMarketOdds) - 1.0, MAX_EV);
        double threshold = 0.15; // 역방향은 15% 임계값 (더 보수적)

        String bestAction;
        double bestEv;
        String reason;

        if (ev > threshold) {
            bestAction = betDirection;
            bestEv = ev;
            reason = String.format("🔄역방향 %s EV: +%.1f%% (추정%.0f%% vs 오즈%.0f¢) > 임계값%.0f%%",
                    betDirection, ev * 100, reverseEstProb * 100, rawOdds * 100, threshold * 100);
        } else {
            bestAction = "HOLD";
            bestEv = ev;
            reason = String.format("역방향 EV부족 - %s EV: %+.1f%% (추정%.0f%% vs 오즈%.0f¢, 임계값%.0f%%)",
                    betDirection, ev * 100, reverseEstProb * 100, rawOdds * 100, threshold * 100);
        }

        log.info("[EV-역방향] {} | 추정={}% | 원본오즈={}¢ → 보정오즈={}¢ | ev={}% | {}",
                betDirection,
                String.format("%.0f", reverseEstProb * 100),
                String.format("%.0f", rawOdds * 100),
                String.format("%.0f", reverseMarketOdds * 100),
                String.format("%+.1f", ev * 100),
                bestAction);

        double upEv = "UP".equals(betDirection) ? ev : -1;
        double downEv = "DOWN".equals(betDirection) ? ev : -1;
        return new EvResult(upEv, downEv, bestAction, bestEv, threshold, reason);
    }

    /**
     * Kelly Criterion V2 — EV 크기에 비례하는 동적 배팅
     *
     * 기존: 고정 25% Kelly → 항상 2-10%
     * V2: EV가 높을수록 더 많이 배팅 (확신에 비례)
     */
    public double calcBetSize(double balance, double ev, double marketOdds) {
        if (ev <= 0) return 0;
        marketOdds = clamp(marketOdds, REV_MIN_ODDS, FWD_MAX_ODDS);

        double payout = 1.0 / marketOdds;
        double kellyFraction = ev / (payout - 1.0);

        // EV 비례 Kelly 비율: EV 높으면 더 공격적
        double kellyMultiplier;
        if (ev >= 1.0)      kellyMultiplier = 0.35; // EV 100%+ → 35% Kelly
        else if (ev >= 0.5) kellyMultiplier = 0.30; // EV 50-100% → 30% Kelly
        else if (ev >= 0.3) kellyMultiplier = 0.25; // EV 30-50% → 25% Kelly
        else                kellyMultiplier = 0.20; // EV 10-30% → 20% Kelly

        double safeFraction = kellyFraction * kellyMultiplier;
        safeFraction = clamp(safeFraction, 0.02, 0.12); // 2-12% (기존 2-10%)

        return balance * safeFraction;
    }

    /**
     * 역방향 전용 배팅 사이즈 — 좀 더 보수적
     */
    public double calcReverseBetSize(double balance, double ev, double marketOdds) {
        if (ev <= 0) return 0;
        marketOdds = clamp(marketOdds, REV_MIN_ODDS, REV_MAX_ODDS);

        double payout = 1.0 / marketOdds;
        double kellyFraction = ev / (payout - 1.0);

        // 역방향은 Kelly 15-25%로 보수적
        double kellyMultiplier;
        if (ev >= 1.5)      kellyMultiplier = 0.25;
        else if (ev >= 0.8) kellyMultiplier = 0.20;
        else                kellyMultiplier = 0.15;

        double safeFraction = kellyFraction * kellyMultiplier;
        safeFraction = clamp(safeFraction, 0.02, 0.08); // 2-8%

        return balance * safeFraction;
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
