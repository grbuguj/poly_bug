package com.example.poly_bug.util;

/**
 * 코인별 가격 포맷팅 유틸리티
 * XRP: 소수점 4자리 (가격이 $1.52 수준이라 2자리로는 변동이 안 보임)
 * BTC/ETH/SOL: 소수점 2자리
 */
public class PriceFormatter {

    /**
     * 코인에 맞는 소수점 자릿수로 가격 포맷
     * XRP → "1.5234", BTC → "96543.21"
     */
    public static String format(String coin, double price) {
        if ("XRP".equalsIgnoreCase(coin)) {
            return String.format("%.4f", price);
        }
        return String.format("%.2f", price);
    }

    /**
     * $ 기호 + 콤마 포함 포맷
     * XRP → "$1.5234", BTC → "$96,543.21"
     */
    public static String formatWithSymbol(String coin, double price) {
        if ("XRP".equalsIgnoreCase(coin)) {
            return String.format("$%.4f", price);
        }
        return String.format("$%,.2f", price);
    }

    /**
     * 콤마 포함 포맷 ($ 없이)
     * XRP → "1.5234", BTC → "96,543.21"
     */
    public static String formatWithComma(String coin, double price) {
        if ("XRP".equalsIgnoreCase(coin)) {
            return String.format("%,.4f", price);
        }
        return String.format("%,.2f", price);
    }
}
