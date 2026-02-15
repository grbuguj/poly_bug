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
 * SOL 1H 동적 트리거 스케줄러
 * ⚠️ V2 (OddsGapScanner) 전환으로 비활성화됨
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.legacy-triggers.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class SolTradingScheduler {

    private final TradingService tradingService;
    private final TriggerConfigService triggerConfigService;

    private volatile boolean tradedThisHour = false;

    @Scheduled(cron = "5 * * * * *") // 매분 :05초 (BTC :00, ETH :03과 분산)
    public void checkTrigger() {
        int currentMinute = LocalDateTime.now().getMinute();

        if (currentMinute == 0) {
            tradedThisHour = false;
            log.info("🔄 [SOL] 시간당 배팅 플래그 리셋");
            return;
        }

        if (tradedThisHour) return;

        double evThreshold = triggerConfigService.getEvThresholdForMinute("SOL", currentMinute);
        if (evThreshold < 0) return;

        TriggerConfigService.TriggerSet config = triggerConfigService.getConfig("SOL");
        int triggerIndex = -1;
        for (int i = 0; i < config.getMinutes().length; i++) {
            if (config.getMinutes()[i] == currentMinute) { triggerIndex = i + 1; break; }
        }

        log.info("⏰ [SOL] 트리거{} (:{}) — EV 임계값 {}%",
                triggerIndex, String.format("%02d", currentMinute), (int)(evThreshold * 100));

        boolean traded = tradingService.executeMomentumCycle("SOL", "1H", evThreshold);
        if (traded) {
            tradedThisHour = true;
            log.info("✅ [SOL] :{} 배팅 완료 — 이번 시간 추가 배팅 없음",
                    String.format("%02d", currentMinute));
        } else if (triggerIndex == 2) {
            log.info("⏸️ [SOL] 이번 시간 2번 모두 패스 — 배팅 없음");
        }
    }
}
