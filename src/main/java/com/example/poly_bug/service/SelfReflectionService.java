package com.example.poly_bug.service;

import com.example.poly_bug.entity.ReflectionLog;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.ReflectionLogRepository;
import com.example.poly_bug.repository.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SelfReflectionService {

    private final TradeRepository tradeRepository;
    private final ReflectionLogRepository reflectionLogRepository;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    // 배팅 결과가 나왔을 때 자기반성 실행
    public void reflect(Trade trade) {
        if (trade.getResult() == Trade.TradeResult.PENDING) return;

        try {
            String prompt = """
                    너는 방금 Polymarket 배팅이 끝났어. 결과를 분석하고 배운 점을 정리해줘.
                    
                    마켓: %s
                    행동: %s
                    배팅 금액: $%.2f
                    결과: %s
                    손익: $%.2f
                    당시 판단 이유: %s
                    
                    이 결과를 바탕으로:
                    1. 왜 이런 결과가 나왔는지
                    2. 다음 배팅에서 개선할 점
                    3. 이 패턴을 기억해야 하는 이유
                    
                    간결하게 2-3줄로 한국어로 작성해줘.
                    """.formatted(
                    trade.getMarketTitle(),
                    trade.getAction(),
                    trade.getBetAmount(),
                    trade.getResult(),
                    trade.getProfitLoss() != null ? trade.getProfitLoss() : 0.0,
                    trade.getReason()
            );

            String reflection = callClaude(prompt);
            trade.setReflection(reflection);
            tradeRepository.save(trade);

            // 전체 반성 로그 저장
            saveReflectionLog(reflection);
            log.info("자기반성 완료: {}", reflection.substring(0, Math.min(100, reflection.length())));

        } catch (Exception e) {
            log.error("자기반성 실패: {}", e.getMessage());
        }
    }

    private void saveReflectionLog(String content) {
        Long wins = tradeRepository.countWins();
        Long resolved = tradeRepository.countResolved();
        Double totalPnl = tradeRepository.totalProfitLoss();
        double winRate = resolved > 0 ? (double) wins / resolved * 100 : 0;

        ReflectionLog log = ReflectionLog.builder()
                .content(content)
                .winRate(winRate)
                .totalTrades(resolved.intValue())
                .totalProfitLoss(totalPnl)
                .build();
        reflectionLogRepository.save(log);
    }

    private String callClaude(String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("model", model);
            put("max_tokens", 512);
            put("messages", List.of(new java.util.HashMap<>() {{
                put("role", "user");
                put("content", prompt);
            }}));
        }});

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").get(0).path("text").asText();
        }
    }
}
