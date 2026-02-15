package com.example.poly_bug.scheduler;

import com.example.poly_bug.service.TradingService;
import com.example.poly_bug.service.TriggerConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * XRP 1H 동적 트리거 스케줄러
 * ⚠️ V2 (OddsGapScanner) 전환으로 비활성화됨
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.legacy-triggers.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class XrpTradingScheduler {

    private final TradingService tradingService;
    private final TriggerConfigService triggerConfigService;

    private volatile boolean tradedThisHour = false;

    @Scheduled(cron = "8 * * * * *") // 매분 :08초 (BTC :00, ETH :03, SOL :05와 분산)
    public void checkTrigger() {
        int currentMinute = LocalDateTime.now().getMinute();

        if (currentMinute == 0) {
            tradedThisHour = false;
            log.info("🔄 [XRP] 시간당 배팅 플래그 리셋");
            return;
        }

        if (tradedThisHour) return;

        double evThreshold = triggerConfigService.getEvThresholdForMinute("XRP", currentMinute);
        if (evThreshold < 0) return;

        TriggerConfigService.TriggerSet config = triggerConfigService.getConfig("XRP");
        int triggerIndex = -1;
        for (int i = 0; i < config.getMinutes().length; i++) {
            if (config.getMinutes()[i] == currentMinute) { triggerIndex = i + 1; break; }
        }

        log.info("⏰ [XRP] 트리거{} (:{}) — EV 임계값 {}%",
                triggerIndex, String.format("%02d", currentMinute), (int)(evThreshold * 100));

        boolean traded = tradingService.executeMomentumCycle("XRP", "1H", evThreshold);
        if (traded) {
            tradedThisHour = true;
            log.info("✅ [XRP] :{} 배팅 완료 — 이번 시간 추가 배팅 없음",
                    String.format("%02d", currentMinute));
        } else if (triggerIndex == 2) {
            log.info("⏸️ [XRP] 이번 시간 2번 모두 패스 — 배팅 없음");
        }
    }
}
