package com.example.poly_bug.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketIndicators {
    // ===== 트레이딩 대상 코인 (범용) =====
    private String targetCoin;        // "BTC", "ETH", "SOL", "XRP" 등
    private double coinPrice;         // 대상 코인 현재가
    private double coinChange1h;      // 대상 코인 1H 변화율
    private double coinChange4h;      // 대상 코인 4H 변화율
    private double coinChange24h;     // 대상 코인 24H 변화율
    private double coinHourOpen;      // 대상 코인 현재 1H 캔들 시가
    private double coin15mOpen;       // 대상 코인 현재 15M 캔들 시가
    private double coin5mOpen;        // 대상 코인 현재 5M 캔들 시가

    // ETH 가격 (상관지표 / 하위호환)
    private double ethPrice;
    private double ethChange1h;
    private double ethChange4h;
    private double ethChange24h;
    private double ethHourOpen;   // 현재 1H 캔들 시가
    private double eth15mOpen;    // 현재 15M 캔들 시가

    // BTC (상관지표 / 하위호환)
    private double btcPrice;
    private double btcChange1h;
    private double btcChange4h;     // BTC 4시간 변화율
    private double btcChange24h;    // BTC 24시간 변화율
    private double btcHourOpen;   // 현재 1H 캔들 시가
    private double btc15mOpen;    // 현재 15M 캔들 시가

    // 거래량
    private double volume1h;         // 최근 1H 거래량 (USDT)

    // 선물 지표
    private double fundingRate;       // 펀딩비 (%)
    private double openInterest;      // 미결제약정 ($)
    private double openInterestChange; // OI 변화율 (%, 30분 전 대비 - 1H용)
    private double openInterestChange5m; // OI 변화율 (%, 5분 전 대비 - 15M용)
    private double longShortRatio;    // 롱/숏 비율

    // 심리 지표
    private int fearGreedIndex;
    private String fearGreedLabel;    // "극도 공포", "공포", "중립", "탐욕", "극도 탐욕"

    // 추세 판단
    private String trend;             // "UPTREND", "DOWNTREND", "SIDEWAYS"

    // 기술적 지표 (1H 캔들 기반)
    private double rsi;               // RSI (0~100)
    private double macd;              // MACD 히스토그램 (macdLine - signal)
    private double macdSignal;        // MACD 시그널
    private double macdLine;          // MACD 라인

    // 기술적 지표 (15M 캔들 기반)
    private double rsi15m;            // 15M RSI (0~100)
    private double macd15m;           // 15M MACD 히스토그램
    private double macdSignal15m;     // 15M MACD 시그널
    private double macdLine15m;       // 15M MACD 라인
}
