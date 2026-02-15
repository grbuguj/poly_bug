package com.example.poly_bug.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동적 트리거 설정 관리 (pending/active 2단계)
 *
 * - active: 현재 시간에 스케줄러가 사용하는 트리거
 * - pending: 분석 결과로 대기 중인 트리거 (다음 정각에 active로 승격)
 *
 * 흐름:
 *   1) 사용자가 분석 실행 → pending에 저장
 *   2) 매시 정각(:00) → pending이 있으면 active로 승격
 *   3) 스케줄러는 항상 active만 참조
 */
@Slf4j
@Service
public class TriggerConfigService {

    @Getter
    public static class TriggerSet {
        private final int[] minutes;
        private final double[] evThresholds;
        private final double[] accuracies;
        private final String source;
        private final LocalDateTime updatedAt;

        public TriggerSet(int[] minutes, double[] evThresholds, double[] accuracies, String source) {
            this.minutes = minutes;
            this.evThresholds = evThresholds;
            this.accuracies = accuracies;
            this.source = source;
            this.updatedAt = LocalDateTime.now();
        }
    }

    /** 스케줄러가 실제 사용하는 트리거 */
    private final Map<String, TriggerSet> active = new ConcurrentHashMap<>();

    /** 분석 결과 대기 중 (다음 정각에 적용) */
    private final Map<String, TriggerSet> pending = new ConcurrentHashMap<>();

    public TriggerConfigService() {
        // 1H 모멘텀: 42분(탐색) + 52분(확인) — 캔들 후반부 진입
        TriggerSet default1H = new TriggerSet(
                new int[]{42, 52},
                new double[]{0.12, 0.10},
                new double[]{0, 0},
                "모멘텀 기본값"
        );
        active.put("BTC", default1H);
        active.put("ETH", default1H);

        // 15M 극단적 제한: 윈도우 내 10분(확인만), EV 30%+
        TriggerSet default15M = new TriggerSet(
                new int[]{10, 13},
                new double[]{0.30, 0.25},
                new double[]{0, 0},
                "15M 극제한 기본값"
        );
        active.put("BTC_15M", default15M);
        active.put("ETH_15M", default15M);
    }

    /** 스케줄러용: active 트리거 조회 */
    public TriggerSet getConfig(String coin) {
        return active.getOrDefault(coin, active.get("BTC"));
    }

    /** 스케줄러용: 현재 분이 active 트리거에 해당하면 EV 임계값 반환, 아니면 -1 */
    public double getEvThresholdForMinute(String coin, int currentMinute) {
        TriggerSet ts = getConfig(coin);
        for (int i = 0; i < ts.minutes.length; i++) {
            if (ts.minutes[i] == currentMinute) {
                return ts.evThresholds[i];
            }
        }
        return -1;
    }

    /**
     * 분석 결과 → pending에 저장 (즉시 적용 안 함)
     */
    public void updateFromAnalysis(String coin, int[] minutes, double[] accuracies, String source) {
        double[] evThresholds = new double[2];
        // 1차(탐색): EV 기준 높게, 2차(확인): EV 기준 낮게
        for (int i = 0; i < 2; i++) {
            double acc = accuracies[i];
            if (acc >= 0.72) evThresholds[i] = 0.10;
            else if (acc >= 0.68) evThresholds[i] = 0.12;
            else if (acc >= 0.65) evThresholds[i] = 0.15;
            else if (acc >= 0.60) evThresholds[i] = 0.18;
            else evThresholds[i] = 0.20;
        }

        TriggerSet newSet = new TriggerSet(minutes, evThresholds, accuracies, source);
        pending.put(coin, newSet);

        log.info("📋 [{}] 트리거 대기(pending): {}분/{}분 → 다음 정각에 적용 [{}]",
                coin, minutes[0], minutes[1], source);
    }

    /**
     * 매시 정각에 호출: pending → active 승격
     * @return 승격된 코인 목록
     */
    public List<String> promotePending() {
        List<String> promoted = new ArrayList<>();
        for (String key : List.of("BTC", "ETH", "BTC_15M", "ETH_15M")) {
            if (promotePendingFor(key)) {
                promoted.add(key);
            }
        }
        return promoted;
    }

    /** 특정 코인만 pending → active 승격 */
    public boolean promotePendingFor(String coin) {
        TriggerSet p = pending.remove(coin);
        if (p != null) {
            active.put(coin, p);
            log.info("✅ [{}] 트리거 적용(active): {}분(EV{}%) / {}분(EV{}%) [{}]",
                    coin,
                    p.minutes[0], (int) (p.evThresholds[0] * 100),
                    p.minutes[1], (int) (p.evThresholds[1] * 100),
                    p.source);
            return true;
        }
        return false;
    }

    /** pending 존재 여부 */
    public boolean hasPending(String coin) {
        return pending.containsKey(coin);
    }

    /** pending 조회 (UI 표시용) */
    public TriggerSet getPending(String coin) {
        return pending.get(coin);
    }

    /** API 응답용 Map (active + pending 상태 포함) */
    public Map<String, Object> toMap(String coin) {
        TriggerSet ts = getConfig(coin);
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (int i = 0; i < ts.minutes.length; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("minute", ts.minutes[i]);
            t.put("evThreshold", ts.evThresholds[i]);
            t.put("accuracy", ts.accuracies[i]);
            triggers.add(t);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coin", coin);
        result.put("triggers", triggers);
        result.put("source", ts.source);
        result.put("updatedAt", ts.updatedAt != null ? ts.updatedAt.toString() : null);

        // pending 정보
        TriggerSet p = pending.get(coin);
        if (p != null) {
            List<Map<String, Object>> pendingTriggers = new ArrayList<>();
            for (int i = 0; i < p.minutes.length; i++) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("minute", p.minutes[i]);
                t.put("evThreshold", p.evThresholds[i]);
                t.put("accuracy", p.accuracies[i]);
                pendingTriggers.add(t);
            }
            result.put("pending", pendingTriggers);
            result.put("pendingSource", p.source);
        }

        return result;
    }
}
