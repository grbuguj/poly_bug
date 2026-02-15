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
 * 15M 모멘텀 스케줄러 (전 코인 지원)
 * ⚠️ V2 (OddsGapScanner) 전환으로 비활성화됨
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.legacy-triggers.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class TradingScheduler15m {

    private final TradingService tradingService;
    private final TriggerConfigService triggerConfigService;

    private volatile boolean btcTradedThisHour = false;
    private volatile boolean ethTradedThisHour = false;
    private volatile boolean solTradedThisHour = false;
    private volatile boolean xrpTradedThisHour = false;
    private volatile int lastWindowStart = -1;

    @Scheduled(cron = "20 * * * * *")
    public void checkTrigger() {
        int currentMinute = LocalDateTime.now().getMinute();
        int windowStart = (currentMinute / 15) * 15;
        int offsetInWindow = currentMinute - windowStart;

        // 정각: 시간당 플래그 리셋
        if (currentMinute == 0) {
            btcTradedThisHour = false;
            ethTradedThisHour = false;
            solTradedThisHour = false;
            xrpTradedThisHour = false;
            log.info("🔄 [15M] 시간당 배팅 플래그 리셋 (BTC/ETH/SOL/XRP)");
        }

        // 윈도우 전환 감지 → pending 승격
        if (windowStart != lastWindowStart) {
            lastWindowStart = windowStart;
            for (String coin : new String[]{"BTC", "ETH", "SOL", "XRP"}) {
                triggerConfigService.promotePendingFor(coin + "_15M");
            }
        }

        // BTC 15M
        if (!btcTradedThisHour) {
            btcTradedThisHour = tryTrade("BTC", offsetInWindow, windowStart);
        }

        // ETH 15M
        if (!ethTradedThisHour) {
            ethTradedThisHour = tryTrade("ETH", offsetInWindow, windowStart);
        }

        // SOL 15M
        if (!solTradedThisHour) {
            solTradedThisHour = tryTrade("SOL", offsetInWindow, windowStart);
        }

        // XRP 15M
        if (!xrpTradedThisHour) {
            xrpTradedThisHour = tryTrade("XRP", offsetInWindow, windowStart);
        }
    }

    private boolean tryTrade(String coin, int offsetInWindow, int windowStart) {
        String configKey = coin + "_15M";
        double evThreshold = triggerConfigService.getEvThresholdForMinute(configKey, offsetInWindow);
        if (evThreshold < 0) return false;

        log.info("⏰ [{} 15M] 트리거 (윈도우 :{} +{}분) — EV 임계값 {}%",
                coin, String.format("%02d", windowStart), offsetInWindow, (int)(evThreshold * 100));

        boolean traded = tradingService.executeMomentumCycle(coin, "15M", evThreshold);
        if (traded) {
            log.info("✅ [{} 15M] 배팅 완료 — 이번 시간 추가 배팅 없음", coin);
        }
        return traded;
    }
}
