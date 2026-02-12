package com.example.poly_bug.scheduler;

import com.example.poly_bug.service.BotStateService;
import com.example.poly_bug.service.TradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EthTradingScheduler {

    private final TradingService tradingService;
    private final BotStateService botStateService;

    // 55분마다 실행, 초기 지연 45초 (BTC 스케줄러와 30초 차이)
    @Scheduled(fixedDelay = 3300000, initialDelay = 45000)
    public void run() {
        if (!botStateService.isRunning()) return;
        log.info("=== ETH 1H 사이클 시작 ===");
        tradingService.executeCycle("ETH");
    }
}
