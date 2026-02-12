package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeDecisionEngine {

    private final TradeRepository tradeRepository;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    /**
     * coin별 독립 1시간 방향 판단
     */
    public TradeDecision decide(MarketIndicators indicators, double balance, String coin) {
        try {
            String prompt = buildPrompt(indicators, balance, coin);
            String response = callClaude(prompt);
            return parseDecision(response, indicators, coin);
        } catch (Exception e) {
            log.error("Claude 판단 실패 [{}]: {}", coin, e.getMessage());
            return TradeDecision.builder()
                    .action(Trade.TradeAction.HOLD)
                    .confidence(0)
                    .reason("Claude 판단 실패: " + e.getMessage())
                    .amount(0.0)
                    .coin(coin)
                    .build();
        }
    }

    // 하위 호환
    public TradeDecision decide(MarketIndicators indicators, double balance) {
        return decide(indicators, balance, "ETH");
    }

    private String buildPrompt(MarketIndicators m, double balance, String coin) {
        String patternStats = buildPatternStats(m, coin);

        boolean isBtc = "BTC".equals(coin);
        double currentPrice = isBtc ? m.getBtcPrice() : m.getEthPrice();
        String coinSpecific = isBtc
                ? "- BTC는 거시경제(금리, 달러지수)에 민감하게 반응\n"
                  + "- CME 선물 마감/오픈 시간대 변동성 주의\n"
                  + "- BTC 도미넌스 상승 = 알트 약세 = BTC 강세 경향\n"
                : "- ETH는 DeFi/NFT 이벤트, 가스비 변화에 민감\n"
                  + "- BTC 대비 ETH 상대강도 체크 필요\n"
                  + "- ETH/BTC 비율 하락 중이면 ETH 약세 경향\n";

        return String.format("너는 Polymarket의 '%s Up or Down - 1 Hour' 마켓 전문 트레이더야.\n", coin)
                + String.format("지금부터 1시간 후 %s 가격이 현재보다 높으면 YES(Up), 낮으면 NO(Down)이야.\n\n", coin)

                + "=== 현재 시장 데이터 ===\n"
                + String.format("%s 현재가: $%.2f\n", coin, currentPrice)
                + String.format("%s 1시간 변화: %+.2f%%\n", coin, isBtc ? m.getBtcChange1h() : m.getEthChange1h())
                + String.format("ETH 4시간 변화: %+.2f%%\n", m.getEthChange4h())
                + String.format("ETH 24시간 변화: %+.2f%%\n", m.getEthChange24h())
                + (isBtc ? "" : String.format("BTC 1시간 변화: %+.2f%%\n", m.getBtcChange1h()))
                + String.format("현재 추세: %s\n\n", m.getTrend())
                + "=== " + coin + " 특성 ===\n"
                + coinSpecific + "\n"

                + "=== 선물 시장 (매우 중요) ===\n"
                + String.format("펀딩비: %+.4f%% (양수=롱 과열/하락 신호, 음수=숏 과열/상승 신호)\n", m.getFundingRate())
                + String.format("미결제약정 변화: %+.2f%% (증가=추세 강화, 감소=추세 약화)\n", m.getOpenInterestChange())
                + String.format("롱/숏 비율: %.2f (1.0 기준, 높을수록 롱 많음)\n\n", m.getLongShortRatio())

                + "=== 시장 심리 ===\n"
                + String.format("공포탐욕지수: %d (%s)\n\n", m.getFearGreedIndex(), m.getFearGreedLabel())

                + "=== 과거 패턴 통계 ===\n"
                + patternStats + "\n"

                + "=== 판단 기준 ===\n"
                + "- 펀딩비 +0.05% 이상 → DOWN 고려 (롱 과열)\n"
                + "- 펀딩비 -0.05% 이하 → UP 고려 (숏 과열)\n"
                + "- OI 감소 + 가격 하락 → 추세 지속 가능성\n"
                + "- 극도 공포(0~20) → 반등 가능성\n"
                + "- 극도 탐욕(80~100) → 조정 가능성\n"
                + "- BTC와 ETH 방향 일치할 때 신뢰도 높음\n\n"

                + "=== 잔액 ===\n"
                + String.format("현재 잔액: $%.2f\n\n", balance)

                + "다음 형식으로만 답해 (다른 말 하지 마):\n"
                + "ACTION: UP 또는 DOWN 또는 HOLD\n"
                + "CONFIDENCE: 숫자만 (50~95)\n"
                + "AMOUNT: 배팅금액 (잔액의 5~15%, 확신 낮으면 적게)\n"
                + "REASON: 한국어로 핵심 근거 2~3줄\n";
    }

    private String buildPatternStats(MarketIndicators m, String coin) {
        List<Trade> recentTrades = tradeRepository.findTop50ByCoinOrderByCreatedAtDesc(coin);
        if (recentTrades.isEmpty()) return "아직 패턴 데이터 없음 (첫 배팅)";

        long total = recentTrades.size();
        long wins = recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        long resolved = recentTrades.stream()
                .filter(t -> t.getResult() != Trade.TradeResult.PENDING).count();

        if (resolved == 0) return "아직 결과 확정 없음";

        double winRate = (double) wins / resolved * 100;

        // 현재 펀딩비와 비슷한 조건 필터
        long similarWins = recentTrades.stream()
                .filter(t -> t.getResult() == Trade.TradeResult.WIN
                        && t.getFundingRate() != null
                        && Math.signum(t.getFundingRate()) == Math.signum(m.getFundingRate()))
                .count();
        long similarTotal = recentTrades.stream()
                .filter(t -> t.getResult() != Trade.TradeResult.PENDING
                        && t.getFundingRate() != null
                        && Math.signum(t.getFundingRate()) == Math.signum(m.getFundingRate()))
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("전체 승률: %.1f%% (%d건 중 %d승)\n", winRate, resolved, wins));
        if (similarTotal > 0) {
            sb.append(String.format("현재와 유사한 펀딩비 조건 승률: %.1f%% (%d건)\n",
                    (double) similarWins / similarTotal * 100, similarTotal));
        }

        // 최근 3건 결과
        sb.append("최근 결과: ");
        recentTrades.stream()
                .filter(t -> t.getResult() != Trade.TradeResult.PENDING)
                .limit(3)
                .forEach(t -> sb.append(t.getResult() == Trade.TradeResult.WIN ? "✅" : "❌"));

        return sb.toString();
    }

    private TradeDecision parseDecision(String response, MarketIndicators indicators, String coin) {
        try {
            String action = "HOLD";
            int confidence = 50;
            double amount = 0.0;
            String reason = response;

            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("ACTION:")) action = line.replace("ACTION:", "").trim();
                else if (line.startsWith("CONFIDENCE:")) confidence = Integer.parseInt(line.replace("CONFIDENCE:", "").trim());
                else if (line.startsWith("AMOUNT:")) amount = Double.parseDouble(line.replace("AMOUNT:", "").replace("$", "").trim());
                else if (line.startsWith("REASON:")) reason = line.replace("REASON:", "").trim();
            }

            Trade.TradeAction tradeAction = switch (action.toUpperCase()) {
                case "UP" -> Trade.TradeAction.BUY_YES;
                case "DOWN" -> Trade.TradeAction.BUY_NO;
                default -> Trade.TradeAction.HOLD;
            };

            // 확신도 70% 미만이면 HOLD
            if (confidence < 70) {
                tradeAction = Trade.TradeAction.HOLD;
                reason = "[확신도 부족 " + confidence + "%] " + reason;
            }

            return TradeDecision.builder()
                    .action(tradeAction)
                    .confidence(confidence)
                    .amount(amount)
                    .reason(reason)
                    .marketId(coin.toLowerCase() + "-1h-updown")
                    .marketTitle(coin + " Up or Down - 1 Hour")
                    .coin(coin)
                    .timeframe("1H")
                    .build();

        } catch (Exception e) {
            log.error("응답 파싱 실패: {}", response);
            return TradeDecision.builder()
                    .action(Trade.TradeAction.HOLD)
                    .confidence(0)
                    .reason("파싱 실패: " + response)
                    .amount(0.0)
                    .build();
        }
    }

    private String callClaude(String prompt) throws Exception {
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "user");
        messageNode.put("content", prompt);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(messageNode);

        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("max_tokens", 300);
        requestNode.set("messages", messages);

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(okhttp3.RequestBody.create(
                        objectMapper.writeValueAsString(requestNode),
                        MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) throw new RuntimeException("빈 응답");
            String body = response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("Claude API 오류 " + response.code() + ": " + body);
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").get(0).path("text").asText();
        }
    }
}
