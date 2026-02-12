package com.example.poly_bug.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class BotStateService {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Getter
    private LocalDateTime lastRunAt;

    @Getter
    private LocalDateTime startedAt;

    @Getter
    private int cycleCount = 0;

    @Getter
    private String lastAction = "대기 중";

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        running.set(true);
        startedAt = LocalDateTime.now();
        log.info("봇 시작됨");
    }

    public void stop() {
        running.set(false);
        log.info("봇 정지됨");
    }

    public void recordCycle(String action) {
        lastRunAt = LocalDateTime.now();
        cycleCount++;
        lastAction = action;
    }
}
