package com.example.poly_bug.controller;

import com.example.poly_bug.dto.MarketData;
import com.example.poly_bug.entity.ReflectionLog;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.ReflectionLogRepository;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.service.BotStateService;
import com.example.poly_bug.service.PolymarketClient;
import com.example.poly_bug.service.TradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final TradeRepository tradeRepository;
    private final ReflectionLogRepository reflectionLogRepository;
    private final PolymarketClient polymarketClient;
    private final TradingService tradingService;
    private final BotStateService botStateService;

    // OkHttpClient, ObjectMapper는 final 필드가 아닌 일반 필드로 선언
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    @Value("${trading.dry-run}")
    private boolean dryRun;

    // @RequiredArgsConstructor 대신 직접 생성자 작성
    public ChatController(TradeRepository tradeRepository,
                          ReflectionLogRepository reflectionLogRepository,
                          PolymarketClient polymarketClient,
                          TradingService tradingService,
                          BotStateService botStateService) {
        this.tradeRepository = tradeRepository;
        this.reflectionLogRepository = reflectionLogRepository;
        this.polymarketClient = polymarketClient;
        this.tradingService = tradingService;
        this.botStateService = botStateService;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.get("message");
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.ok(Map.of("reply", "메시지를 입력해주세요."));
        }

        try {
            // 1. 명령어 먼저 체크 (Claude 호출 없이 즉시 처리)
            String msg = userMessage;
            if (containsAny(msg, "지금 배팅", "즉시 배팅", "바로 배팅", "배팅 실행", "1회 실행", "run now")) {
                new Thread(() -> { tradingService.executeCycle("BTC"); tradingService.executeCycle("ETH"); }).start();
                return ResponseEntity.ok(Map.of("reply", "⚡ 배팅 사이클 시작했어요! 왼쪽 로그를 확인하세요."));
            }

            // 2. 일반 질문 → 컨텍스트 조립 후 Claude 호출
            String context = buildContext();
            String prompt = buildChatPrompt(userMessage, context);
            String reply = callClaude(prompt);
            return ResponseEntity.ok(Map.of("reply", reply));

        } catch (Exception e) {
            log.error("챗봇 오류: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("reply", "⚠️ 오류가 발생했어요: " + e.getMessage()));
        }
    }

    // 현재 봇 상태 + DB 데이터 + 마켓 정보 수집
    private String buildContext() {
        StringBuilder ctx = new StringBuilder();

        // 봇 상태
        ctx.append("=== 봇 상태 ===\n");
        ctx.append("실행 중: YES (항상 자동 실행)\n");
        ctx.append("총 사이클: ").append(botStateService.getCycleCount()).append("회\n");
        ctx.append("마지막 행동: ").append(botStateService.getLastAction()).append("\n");
        ctx.append("DRY-RUN 모드: ").append(dryRun ? "YES (실제 배팅 안 함)" : "NO (실제 배팅 중)").append("\n\n");

        // 잔액
        try {
            Double balance = polymarketClient.getBalance();
            ctx.append("=== 잔액 ===\n$").append(String.format("%.2f", balance)).append(" USDC\n\n");
        } catch (Exception e) {
            ctx.append("=== 잔액 ===\n조회 실패\n\n");
        }

        // 통계
        Long wins = tradeRepository.countWins();
        Long resolved = tradeRepository.countResolved();
        Double totalPnl = tradeRepository.totalProfitLoss();
        double winRate = resolved > 0 ? (double) wins / resolved * 100 : 0;
        long totalTrades = tradeRepository.count();

        ctx.append("=== 통계 ===\n");
        ctx.append("총 배팅: ").append(totalTrades).append("건\n");
        ctx.append("승률: ").append(String.format("%.1f", winRate)).append("%\n");
        ctx.append("누적 손익: $").append(String.format("%.2f", totalPnl)).append("\n");
        ctx.append("승/결과: ").append(wins).append("/").append(resolved).append("\n\n");

        // 최근 배팅 5건
        List<Trade> recentTrades = tradeRepository.findTop20ByOrderByCreatedAtDesc()
                .stream().limit(5).toList();
        ctx.append("=== 최근 배팅 ===\n");
        if (recentTrades.isEmpty()) {
            ctx.append("배팅 기록 없음\n");
        } else {
            for (Trade t : recentTrades) {
                String createdAt = t.getCreatedAt() != null
                        ? t.getCreatedAt().toString().substring(5, 16) : "N/A";
                String title = t.getMarketTitle() != null
                        ? t.getMarketTitle().substring(0, Math.min(40, t.getMarketTitle().length())) : "N/A";
                String reason = t.getReason() != null
                        ? t.getReason().substring(0, Math.min(60, t.getReason().length())) : "없음";
                ctx.append(String.format("[%s] %s → %s | $%.2f | 확신 %d%% | %s | 결과: %s\n",
                        createdAt, title, t.getAction(),
                        t.getBetAmount() != null ? t.getBetAmount() : 0.0,
                        t.getConfidence() != null ? t.getConfidence() : 0,
                        reason, t.getResult()));
            }
        }
        ctx.append("\n");

        // 현재 활성 마켓
        try {
            List<MarketData> markets = polymarketClient.getActiveMarkets();
            ctx.append("=== 현재 활성 마켓 (상위 5개) ===\n");
            markets.stream().limit(5).forEach(m -> {
                String title = m.getTitle().substring(0, Math.min(50, m.getTitle().length()));
                ctx.append(String.format("- %s | YES: %.1f%% | NO: %.1f%% | Vol: $%s\n",
                        title, m.getYesPrice() * 100, m.getNoPrice() * 100, m.getVolume()));
            });
        } catch (Exception e) {
            ctx.append("=== 현재 마켓 ===\n조회 실패\n");
        }
        ctx.append("\n");

        // 최근 AI 반성 일지
        List<ReflectionLog> reflections = reflectionLogRepository.findTop5ByOrderByCreatedAtDesc();
        if (!reflections.isEmpty()) {
            ctx.append("=== 최근 AI 반성 일지 ===\n");
            reflections.stream().limit(3).forEach(r -> {
                String content = r.getContent() != null
                        ? r.getContent().substring(0, Math.min(100, r.getContent().length())) : "없음";
                ctx.append("- ").append(content).append("\n");
            });
        }

        return ctx.toString();
    }

    private String buildChatPrompt(String userMessage, String context) {
        return "너는 PolyBug 트레이딩 봇의 전담 AI 어시스턴트야.\n"
                + "사용자가 봇 상태, 배팅 현황, 마켓 분석에 대해 물어보면 친절하고 간결하게 한국어로 답해줘.\n\n"
                + "아래 데이터를 참고해서 정확하게 답해:\n\n"
                + context + "\n"
                + "=== 사용자 질문 ===\n"
                + userMessage + "\n\n"
                + "규칙:\n"
                + "- 한국어로 답해\n"
                + "- 간결하게 (3-5줄 이내)\n"
                + "- 수치는 정확하게\n"
                + "- 친근한 말투로\n";
    }

    // Jackson ObjectNode로 안전하게 JSON 구성 (익명 이중 중괄호 제거)
    private String callClaude(String prompt) throws Exception {
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "user");
        messageNode.put("content", prompt);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(messageNode);

        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("max_tokens", 512);
        requestNode.set("messages", messages);

        String requestBody = objectMapper.writeValueAsString(requestNode);

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(okhttp3.RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) throw new RuntimeException("응답 body가 null입니다");
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Claude API 오류 {}: {}", response.code(), responseBody);
                throw new RuntimeException("Claude API 오류 " + response.code());
            }
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("content").get(0).path("text").asText("응답을 받지 못했어요.");
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
