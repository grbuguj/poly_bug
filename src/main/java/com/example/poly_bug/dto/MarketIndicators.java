package com.example.poly_bug.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketIndicators {
    // ETH 가격
    private double ethPrice;
    private double ethChange1h;
    private double ethChange4h;
    private double ethChange24h;

    // BTC
    private double btcPrice;
    private double btcChange1h;

    // 선물 지표
    private double fundingRate;       // 펀딩비 (%)
    private double openInterest;      // 미결제약정 ($)
    private double openInterestChange; // OI 변화율 (%)
    private double longShortRatio;    // 롱/숏 비율

    // 심리 지표
    private int fearGreedIndex;
    private String fearGreedLabel;    // "극도 공포", "공포", "중립", "탐욕", "극도 탐욕"

    // 추세 판단
    private String trend;             // "UPTREND", "DOWNTREND", "SIDEWAYS"
}
