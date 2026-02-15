package com.example.poly_bug.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 봇 상태 추적 (항상 자동 실행, 시작/정지 없음)
 * 사이클 카운트와 마지막 행동만 기록
 */
@Slf4j
@Service
public class BotStateService {

    @Getter
    private LocalDateTime lastRunAt;

    @Getter
    private int cycleCount = 0;

    @Getter
    private String lastAction = "대기 중";

    public void recordCycle(String action) {
        lastRunAt = LocalDateTime.now();
        cycleCount++;
        lastAction = action;
    }
}
