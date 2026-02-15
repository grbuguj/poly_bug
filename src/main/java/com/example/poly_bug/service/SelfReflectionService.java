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
    private final LessonService lessonService;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model-light:claude-haiku-4-5-20251001}")
    private String model;

    // 배팅 결과가 나왔을 때 자기반성 실행
    public void reflect(Trade trade) {
        if (trade.getResult() == Trade.TradeResult.PENDING) return;

        try {
            String prompt = buildReflectionPrompt(trade);
            String reflection = callClaude(prompt);
            trade.setReflection(reflection);
            tradeRepository.save(trade);

            // 전체 반성 로그 저장
            saveReflectionLog(reflection);

            // Level 2 교훈 압축 트리거 (5건 누적 시 자동 실행)
            lessonService.onReflectionAdded();

            log.info("자기반성 완료: {}", reflection.substring(0, Math.min(100, reflection.length())));

        } catch (Exception e) {
            log.error("자기반성 실패: {}", e.getMessage());
        }
    }

    private String buildReflectionPrompt(Trade trade) {
        String dir = trade.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN";
        String result = trade.getResult().name();
        String tf = trade.getTimeframe() != null ? trade.getTimeframe() : "1H";
        boolean isWin = trade.getResult() == Trade.TradeResult.WIN;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Polymarket %s %s 배팅 결과 분석. 구체적 실수/성공 포인트만 짚어줘.\n\n", trade.getCoin(), tf));

        // 가격 데이터
        sb.append("=== 실제 데이터 ===\n");
        if (trade.getOpenPrice() != null) sb.append(String.format("시초가: $%,.2f\n", trade.getOpenPrice()));
        if (trade.getEntryPrice() != null) sb.append(String.format("진입시 현재가: $%,.2f\n", trade.getEntryPrice()));
        if (trade.getExitPrice() != null) sb.append(String.format("종가(판정가): $%,.2f\n", trade.getExitPrice()));
        if (trade.getOpenPrice() != null && trade.getExitPrice() != null) {
            double diff = trade.getExitPrice() - trade.getOpenPrice();
            sb.append(String.format("실제 결과: %s (시초가 대비 %+.2f)\n", diff >= 0 ? "UP" : "DOWN", diff));
            sb.append(String.format("내 배팅: %s → %s\n", dir, result));
        }

        // 당시 시장 조건
        sb.append("\n=== 배팅 시점 시장 조건 ===\n");
        if (trade.getFundingRate() != null) sb.append(String.format("펀딩비: %+.4f%%\n", trade.getFundingRate()));
        if (trade.getOpenInterestChange() != null) sb.append(String.format("OI 변화: %+.2f%%\n", trade.getOpenInterestChange()));
        if (trade.getMarketTrend() != null) sb.append(String.format("추세: %s\n", trade.getMarketTrend()));
        if (trade.getFearGreedIndex() != null) sb.append(String.format("공포탐욕: %d\n", trade.getFearGreedIndex()));

        // 당시 판단 이유
        sb.append(String.format("\n당시 판단: %s\n", trade.getReason()));
        sb.append(String.format("확신도: %d%% | 금액: $%.2f | 손익: $%.2f\n",
                trade.getConfidence(),
                trade.getBetAmount(),
                trade.getProfitLoss() != null ? trade.getProfitLoss() : 0.0));

        // 반성 지시
        sb.append("\n=== 반성 규칙 ===\n");
        sb.append("절대 하지 말 것:\n");
        sb.append("- '타임프레임이 짧아서' 같은 일반론 금지. 이미 이 타임프레임에서 트레이딩하기로 했음.\n");
        sb.append("- '다른 타임프레임에 집중하라' 금지.\n");
        sb.append("- 마크다운 서식(##, **, 번호) 금지. 평문으로만.\n\n");
        sb.append("반드시 할 것 (2~3줄 평문):\n");

        if (isWin) {
            sb.append("- 어떤 신호를 정확히 읽었는지 (예: 'RSI 28 과매도에서 반등 포착')\n");
            sb.append("- 이 패턴이 재현 가능한지, 운인지\n");
        } else {
            sb.append("- 어떤 신호를 잘못 읽었는지 구체적으로 (예: '펀딩비 과열 신호를 무시했다')\n");
            sb.append("- 시초가→종가 방향 변화의 원인 추정 (예: '진입 후 2분만에 반전, OI 급감이 선행지표였다')\n");
            sb.append("- 같은 조건에서 다음번 행동 규칙 (예: 'OI -3% 이상 감소 중이면 HOLD')\n");
        }

        return sb.toString();
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
