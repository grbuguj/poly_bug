package com.example.poly_bug.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ===== 마켓 정보 =====
    private String marketId;
    private String marketTitle;
    private String timeframe; // "1H", "15M", "1D"
    private String coin;      // "ETH", "BTC" 등

    // ===== 배팅 정보 =====
    @Enumerated(EnumType.STRING)
    private TradeAction action; // BUY_YES(Up), BUY_NO(Down), HOLD

    private Double betAmount;
    private Double entryPrice;   // 배팅 시점 ETH 가격
    private Double exitPrice;    // 결과 시점 ETH 가격
    private Integer confidence;  // Claude 확신도 (0~100)

    @Column(length = 2000)
    private String reason;       // Claude 판단 근거

    // ===== 배팅 당시 시장 지표 (패턴 학습용) =====
    private Double fundingRate;       // 펀딩비 (%)
    private Double openInterestChange; // OI 변화율 (%)
    private Double btcChange1h;        // BTC 1시간 변화율
    private Double ethChange1h;        // ETH 1시간 변화율
    private Double ethChange4h;        // ETH 4시간 변화율
    private Double ethChange24h;       // ETH 24시간 변화율
    private Integer fearGreedIndex;    // 공포탐욕지수 (0~100)
    private String marketTrend;        // "UPTREND", "DOWNTREND", "SIDEWAYS"

    // ===== 결과 =====
    @Enumerated(EnumType.STRING)
    private TradeResult result; // WIN, LOSE, PENDING

    private Double profitLoss;

    @Column(length = 2000)
    private String reflection; // AI 반성

    // ===== 시간 =====
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt; // 결과 확정 시간

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.result == null) this.result = TradeResult.PENDING;
    }

    public enum TradeAction {
        BUY_YES, BUY_NO, HOLD
    }

    public enum TradeResult {
        WIN, LOSE, PENDING
    }
}
