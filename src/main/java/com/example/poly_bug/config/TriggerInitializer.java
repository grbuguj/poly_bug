package com.example.poly_bug.config;

import com.example.poly_bug.service.TimingAnalysisService;
import com.example.poly_bug.service.TriggerConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 시 전체 코인 72H 타이밍 분석 자동 실행 → 트리거 즉시 적용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerInitializer {

    private final TimingAnalysisService timingAnalysisService;
    private final TriggerConfigService triggerConfigService;

    private static final String[] ALL_COINS = {"BTC", "ETH", "SOL", "XRP"};

    @EventListener(ApplicationReadyEvent.class)
    public void initTriggers() {
        new Thread(() -> {
            log.info("🚀 서버 시작 → 전체 코인 72H 트리거 자동 분석 시작...");

            // ===== 1H 분석 =====
            for (String coin : ALL_COINS) {
                try {
                    log.info("⏳ [{}] 1H 72H 분석 중...", coin);
                    timingAnalysisService.analyzeOptimalTiming(coin, 72);

                    if (triggerConfigService.hasPending(coin)) {
                        triggerConfigService.promotePendingFor(coin);
                        var config = triggerConfigService.getConfig(coin);
                        log.info("✅ [{}] 1H 트리거 적용 완료: 탐색 {}분 / 확인 {}분 [{}]",
                                coin, config.getMinutes()[0], config.getMinutes()[1],
                                config.getSource());
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [{}] 1H 초기 분석 실패 (기본값 유지): {}", coin, e.getMessage());
                }
            }

            // ===== 15M 분석 =====
            log.info("\n🚀 15M 트리거 분석 시작...");
            for (String coin : ALL_COINS) {
                try {
                    log.info("⏳ [{} 15M] 72H 분석 중...", coin);
                    timingAnalysisService.analyzeOptimalTiming15m(coin, 72);

                    String configKey = coin + "_15M";
                    if (triggerConfigService.hasPending(configKey)) {
                        triggerConfigService.promotePendingFor(configKey);
                        var config = triggerConfigService.getConfig(configKey);
                        log.info("✅ [{} 15M] 트리거 적용: 탐색 +{}분 / 확인 +{}분 [{}]",
                                coin, config.getMinutes()[0], config.getMinutes()[1],
                                config.getSource());
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [{} 15M] 분석 실패 (기본값 유지): {}", coin, e.getMessage());
                }
            }

            log.info("🎯 트리거 초기화 완료 — {} 코인 × (1H + 15M)", ALL_COINS.length);
        }, "trigger-init").start();
    }
}
