package com.example.poly_bug.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Level 2: AI 압축 교훈
 * 반성 기록이 누적되면 Claude가 압축/갱신하는 규칙들.
 * 예: "RSI 70+ 상태에서 UP 배팅 → 75% LOSE (4건 근거)"
 */
@Entity
@Table(name = "trading_lessons")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String lesson;          // 압축된 교훈 텍스트

    private int evidenceCount;      // 근거 트레이드 수

    @Column(length = 50)
    private String category;        // "RSI", "FUNDING", "TIMING", "TREND", "GENERAL" 등

    private double importance;      // 중요도 (0.0 ~ 1.0), 최근 + 빈번할수록 높음

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
