package com.example.poly_bug.service;

import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.entity.TradingLesson;
import com.example.poly_bug.repository.TradingLessonRepository;
import com.example.poly_bug.repository.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Level 2 학습 엔진: 반성 기록 누적 → Claude가 교훈으로 압축/갱신
 * 
 * 트리거: 매 5건 반성 누적 시 (또는 수동 호출)
 * 과정: 최근 반성 10건 + 기존 교훈 → Claude에게 압축 요청 → 교훈 DB 갱신
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LessonService {

    private final TradingLessonRepository lessonRepository;
    private final TradeRepository tradeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicInteger reflectionCounter = new AtomicInteger(0);
    private static final int COMPRESS_EVERY_N = 5; // 5건마다 압축

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model-light:claude-haiku-4-5-20251001}")
    private String model;

    /**
     * 반성 1건 추가될 때마다 호출. N건 누적되면 자동 압축.
     */
    public void onReflectionAdded() {
        int count = reflectionCounter.incrementAndGet();
        if (count >= COMPRESS_EVERY_N) {
            reflectionCounter.set(0);
            compressLessons();
        }
    }

    /**
     * 핵심: 최근 반성 + 트레이드 데이터 → Claude가 교훈 압축
     */
    public void compressLessons() {
        try {
            // 최근 반성 포함된 트레이드 15건
            List<Trade> recentReflected = tradeRepository.findAll().stream()
                    .filter(t -> t.getReflection() != null && !t.getReflection().isBlank())
                    .filter(t -> t.getResult() == Trade.TradeResult.WIN || t.getResult() == Trade.TradeResult.LOSE)
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .limit(15)
                    .toList();

            if (recentReflected.size() < 3) {
                log.info("교훈 압축 스킵: 반성 데이터 부족 ({}건)", recentReflected.size());
                return;
            }

            // 기존 교훈
            List<TradingLesson> existingLessons = lessonRepository.findAllByOrderByImportanceDesc();

            // 프롬프트 구성
            String prompt = buildCompressPrompt(recentReflected, existingLessons);
            String response = callClaude(prompt);

            // 응답 파싱 → 교훈 갱신
            List<TradingLesson> newLessons = parseLessons(response);
            if (!newLessons.isEmpty()) {
                lessonRepository.deleteAll(); // 기존 전체 교체
                lessonRepository.saveAll(newLessons);
                log.info("✅ 교훈 갱신 완료: {}건 (반성 {}건 기반)", newLessons.size(), recentReflected.size());
                messagingTemplate.convertAndSend("/topic/trading",
                        "🧠 AI 교훈 압축 완료: " + newLessons.size() + "개 규칙 갱신 (반성 " + recentReflected.size() + "건 분석)");
            }
        } catch (Exception e) {
            log.error("교훈 압축 실패: {}", e.getMessage());
        }
    }

    private String buildCompressPrompt(List<Trade> trades, List<TradingLesson> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 Polymarket 트레이딩 봇의 학습 엔진이야.\n");
        sb.append("아래 최근 배팅 결과와 반성 기록을 분석하여, 핵심 교훈 5~7개로 압축해줘.\n\n");

        // 기존 교훈
        if (!existing.isEmpty()) {
            sb.append("=== 기존 교훈 (업데이트/삭제/유지 판단) ===\n");
            for (TradingLesson l : existing) {
                sb.append(String.format("- [%s] %s (근거 %d건, 중요도 %.1f)\n",
                        l.getCategory(), l.getLesson(), l.getEvidenceCount(), l.getImportance()));
            }
            sb.append("\n");
        }

        // 최근 트레이드 + 반성
        sb.append("=== 최근 배팅 결과 + 반성 ===\n");
        for (Trade t : trades) {
            String dir = t.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN";
            String result = t.getResult().name();
            sb.append(String.format("[%s %s] %s배팅 → %s | PNL: $%.2f\n",
                    t.getCoin(), t.getTimeframe() != null ? t.getTimeframe() : "1H",
                    dir, result,
                    t.getProfitLoss() != null ? t.getProfitLoss() : 0.0));
            sb.append(String.format("  상황: 펀딩%.4f%% | RSI추정 | 추세:%s | OI변화:%.1f%%\n",
                    t.getFundingRate() != null ? t.getFundingRate() : 0.0,
                    t.getMarketTrend() != null ? t.getMarketTrend() : "?",
                    t.getOpenInterestChange() != null ? t.getOpenInterestChange() : 0.0));
            sb.append(String.format("  반성: %s\n", t.getReflection()));
        }

        sb.append("\n=== 출력 형식 (정확히 따라야 함) ===\n");
        sb.append("각 교훈을 아래 형식으로 5~7개 출력:\n");
        sb.append("LESSON: [교훈 텍스트 - 구체적 조건과 결과 포함, 1줄]\n");
        sb.append("CATEGORY: [RSI / FUNDING / TIMING / TREND / STREAK / OI / GENERAL 중 하나]\n");
        sb.append("EVIDENCE: [근거 트레이드 수 숫자만]\n");
        sb.append("IMPORTANCE: [0.1~1.0 숫자만]\n");
        sb.append("---\n");
        sb.append("\n규칙:\n");
        sb.append("- 기존 교훈 중 여전히 유효한 것은 유지/업데이트 (근거 수 누적)\n");
        sb.append("- 반증된 교훈은 삭제 또는 수정\n");
        sb.append("- 새 패턴 발견하면 추가\n");
        sb.append("- 최대 7개까지만\n");
        sb.append("- 교훈은 구체적 조건+행동으로: '주의하세요' ❌ → 'RSI 70+ UP배팅 75% LOSE → HOLD 권장' ✅\n");
        sb.append("- '타임프레임이 짧아서', '15분은 도박' 같은 일반론 금지. 해당 타임프레임 내에서의 구체적 신호 규칙만.\n");
        sb.append("- 한국어로 작성\n");

        return sb.toString();
    }

    private List<TradingLesson> parseLessons(String response) {
        List<TradingLesson> lessons = new ArrayList<>();
        String currentLesson = null;
        String currentCategory = "GENERAL";
        int currentEvidence = 1;
        double currentImportance = 0.5;

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("LESSON:")) {
                // 이전 교훈 저장
                if (currentLesson != null) {
                    lessons.add(buildLesson(currentLesson, currentCategory, currentEvidence, currentImportance));
                }
                currentLesson = line.substring(7).trim();
                currentCategory = "GENERAL";
                currentEvidence = 1;
                currentImportance = 0.5;
            } else if (line.startsWith("CATEGORY:")) {
                currentCategory = line.substring(9).trim().toUpperCase();
            } else if (line.startsWith("EVIDENCE:")) {
                try { currentEvidence = Integer.parseInt(line.substring(9).trim()); } catch (Exception e) {}
            } else if (line.startsWith("IMPORTANCE:")) {
                try { currentImportance = Double.parseDouble(line.substring(11).trim()); } catch (Exception e) {}
            }
        }
        // 마지막 교훈
        if (currentLesson != null) {
            lessons.add(buildLesson(currentLesson, currentCategory, currentEvidence, currentImportance));
        }

        // 최대 7개
        if (lessons.size() > 7) lessons = lessons.subList(0, 7);
        return lessons;
    }

    private TradingLesson buildLesson(String lesson, String category, int evidence, double importance) {
        return TradingLesson.builder()
                .lesson(lesson)
                .category(category)
                .evidenceCount(evidence)
                .importance(Math.max(0.1, Math.min(1.0, importance)))
                .build();
    }

    /**
     * 현재 교훈 목록 반환 (프롬프트용)
     */
    public List<TradingLesson> getActiveLessons() {
        return lessonRepository.findTop7ByOrderByImportanceDesc();
    }

    private String callClaude(String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("model", model);
            put("max_tokens", 1024);
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
                .post(RequestBody.create(requestBody, okhttp3.MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("Claude API 오류: " + response.code());
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").get(0).path("text").asText();
        }
    }
}
