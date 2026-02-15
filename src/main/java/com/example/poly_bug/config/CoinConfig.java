package com.example.poly_bug.config;

import lombok.Getter;
import java.util.List;

/**
 * 지원 코인 중앙 레지스트리
 *
 * 코인 추가 시 여기만 수정하면 WebSocket, 오즈조회, 오즈지연 전부 자동 확장
 */
@Getter
public class CoinConfig {

    /**
     * 코인 정의
     * @param symbol    바이낸스 심볼 (BTCUSDT)
     * @param label     표시용 (BTC)
     * @param slug      폴리마켓 slug prefix (bitcoin)
     * @param wsStream  바이낸스 WebSocket 스트림명 (btcusdt@trade)
     */
    public record CoinDef(String symbol, String label, String slug, String wsStream) {}

    /**
     * 활성 코인 목록 (폴리마켓 Hourly Up/Down 마켓 존재하는 것만)
     * 2026-02 기준: BTC, ETH, SOL, XRP
     * 여기에 추가만 하면 전체 시스템 자동 확장
     */
    public static final List<CoinDef> ACTIVE_COINS = List.of(
            new CoinDef("BTCUSDT", "BTC", "bitcoin", "btcusdt@trade"),
            new CoinDef("ETHUSDT", "ETH", "ethereum", "ethusdt@trade"),
            new CoinDef("SOLUSDT", "SOL", "solana", "solusdt@trade"),
            new CoinDef("XRPUSDT", "XRP", "xrp", "xrpusdt@trade")
    );

    /**
     * 바이낸스 심볼 → label 변환 (BTCUSDT → BTC)
     */
    public static String symbolToLabel(String symbol) {
        for (CoinDef coin : ACTIVE_COINS) {
            if (coin.symbol.equals(symbol)) return coin.label;
        }
        return symbol.replace("USDT", "");
    }

    /**
     * label → CoinDef 조회
     */
    public static CoinDef getByLabel(String label) {
        for (CoinDef coin : ACTIVE_COINS) {
            if (coin.label.equals(label)) return coin;
        }
        return null;
    }

    /**
     * WebSocket 스트림 URL 생성 (전체 코인)
     */
    public static String buildWsUrl() {
        String streams = ACTIVE_COINS.stream()
                .map(CoinDef::wsStream)
                .reduce((a, b) -> a + "/" + b)
                .orElse("btcusdt@trade");
        return "wss://stream.binance.com:9443/ws/" + streams;
    }
}
