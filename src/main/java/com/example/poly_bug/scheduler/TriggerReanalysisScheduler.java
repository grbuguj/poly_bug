package com.example.poly_bug.scheduler;

import com.example.poly_bug.service.TimingAnalysisService;
import com.example.poly_bug.service.TriggerConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1H + 15M 트리거 주기적 재분석 스케줄러 (전체 코인)
 * ⚠️ V2 (OddsGapScanner) 전환으로 비활성화됨
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.legacy-triggers.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class TriggerReanalysisScheduler {

    private final TimingAnalysisService timingAnalysisService;
    private final TriggerConfigService triggerConfigService;

    private static final String[] ALL_COINS = {"BTC", "ETH", "SOL", "XRP"};

    /**
     * 1H 트리거 재분석: 4시간마다 (00:05, 04:05, 08:05, ...)
     */
    @Scheduled(cron = "0 5 0/4 * * *")
    public void reanalyze1H() {
        log.info("🔄 [1H] 트리거 정기 재분석 시작 (72H) — {} 코인", ALL_COINS.length);

        for (String coin : ALL_COINS) {
            try {
                timingAnalysisService.analyzeOptimalTiming(coin, 72);

                if (triggerConfigService.hasPending(coin)) {
                    var pending = triggerConfigService.getPending(coin);
                    log.info("📋 [{}] 1H 트리거 대기: {}분/{}분 → 다음 정각에 적용 [{}]",
                            coin, pending.getMinutes()[0], pending.getMinutes()[1],
                            pending.getSource());
                }
            } catch (Exception e) {
                log.warn("⚠️ [{}] 1H 재분석 실패: {}", coin, e.getMessage());
            }
        }
    }

    /**
     * 15M 트리거 재분석: 2시간마다 (01:02, 03:02, 05:02, ...)
     */
    @Scheduled(cron = "0 2 1/2 * * *")
    public void reanalyze15M() {
        log.info("🔄 [15M] 트리거 정기 재분석 시작 (48H) — {} 코인", ALL_COINS.length);

        for (String coin : ALL_COINS) {
            String configKey = coin + "_15M";
            try {
                timingAnalysisService.analyzeOptimalTiming15m(coin, 48);

                if (triggerConfigService.hasPending(configKey)) {
                    var pending = triggerConfigService.getPending(configKey);
                    log.info("📋 [{} 15M] 트리거 대기: +{}분/+{}분 → 다음 윈도우에 적용 [{}]",
                            coin, pending.getMinutes()[0], pending.getMinutes()[1],
                            pending.getSource());
                }
            } catch (Exception e) {
                log.warn("⚠️ [{} 15M] 재분석 실패: {}", coin, e.getMessage());
            }
        }
    }
}
