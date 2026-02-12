package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolymarketClient {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${polymarket.base-url}")
    private String baseUrl;

    @Value("${polymarket.api-key}")
    private String apiKey;

    // 활성화된 마켓 목록 조회
    public List<MarketData> getActiveMarkets() {
        List<MarketData> markets = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/markets?active=true&closed=false&limit=20")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Polymarket API 오류: {}", response.code());
                    return getMockMarkets(); // API 오류 시 mock 데이터
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.has("data") ? root.get("data") : root;

                if (data.isArray()) {
                    for (JsonNode market : data) {
                        MarketData md = parseMarket(market);
                        if (md != null) markets.add(md);
                    }
                }
            }
        } catch (Exception e) {
            log.error("마켓 조회 실패: {}", e.getMessage());
            return getMockMarkets();
        }
        return markets;
    }

    private MarketData parseMarket(JsonNode market) {
        try {
            String id = market.path("condition_id").asText(market.path("id").asText());
            String title = market.path("question").asText(market.path("title").asText());
            boolean active = market.path("active").asBoolean(true);
            boolean closed = market.path("closed").asBoolean(false);

            if (!active || closed) return null;

            // YES/NO 가격 파싱
            double yesPrice = 0.5;
            double noPrice = 0.5;
            JsonNode tokens = market.path("tokens");
            if (tokens.isArray()) {
                for (JsonNode token : tokens) {
                    String outcome = token.path("outcome").asText();
                    double price = token.path("price").asDouble(0.5);
                    if ("Yes".equalsIgnoreCase(outcome)) yesPrice = price;
                    if ("No".equalsIgnoreCase(outcome)) noPrice = price;
                }
            }

            return MarketData.builder()
                    .id(id)
                    .title(title)
                    .yesPrice(yesPrice)
                    .noPrice(noPrice)
                    .volume(market.path("volume").asText("0"))
                    .endDate(market.path("end_date_iso").asText(""))
                    .active(true)
                    .build();
        } catch (Exception e) {
            log.error("마켓 파싱 오류: {}", e.getMessage());
            return null;
        }
    }

    // 잔액 조회
    public Double getBalance() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/balance")
                    .addHeader("POLY_API_KEY", apiKey)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return 0.0;
                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                return root.path("balance").asDouble(0.0);
            }
        } catch (Exception e) {
            log.error("잔액 조회 실패: {}", e.getMessage());
            return 100.0; // dry-run 기본값
        }
    }

    // Mock 데이터 (API 연동 전 테스트용)
    public List<MarketData> getMockMarkets() {
        List<MarketData> mocks = new ArrayList<>();
        mocks.add(MarketData.builder()
                .id("mock-btc-1")
                .title("Will BTC price be above $100,000 by end of February 2026?")
                .yesPrice(0.45)
                .noPrice(0.55)
                .volume("250000")
                .endDate("2026-02-28")
                .active(true)
                .build());
        mocks.add(MarketData.builder()
                .id("mock-eth-1")
                .title("Will ETH reach $4,000 before March 2026?")
                .yesPrice(0.32)
                .noPrice(0.68)
                .volume("180000")
                .endDate("2026-03-01")
                .active(true)
                .build());
        mocks.add(MarketData.builder()
                .id("mock-fed-1")
                .title("Will the Fed cut rates in March 2026?")
                .yesPrice(0.61)
                .noPrice(0.39)
                .volume("320000")
                .endDate("2026-03-20")
                .active(true)
                .build());
        return mocks;
    }
}
