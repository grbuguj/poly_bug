package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.dto.TradeDecision;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.entity.TradingLesson;
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
    private final LessonService lessonService;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.model-light:claude-haiku-4-5-20251001}")
    private String modelLight;

    public TradeDecision decide(MarketIndicators indicators, double balance, String coin) {
        return decide(indicators, balance, coin, "1H", null);
    }

    public TradeDecision decide(MarketIndicators indicators, double balance, String coin, String timeframe) {
        return decide(indicators, balance, coin, timeframe, null);
    }

    public TradeDecision decide(MarketIndicators indicators, double balance, String coin, String timeframe,
                                 PolymarketOddsService.MarketOdds odds) {
        try {
            String prompt = buildPrompt(indicators, balance, coin, timeframe, odds);
            String response = callClaude(prompt);
            TradeDecision decision = parseDecision(response, indicators, coin, timeframe);
            // Claude 전체 분석 저장 (프롬프트 요약 + 원본 응답)
            decision.setRawResponse(buildAnalysisRecord(prompt, response));
            return decision;
        } catch (Exception e) {
            log.error("Claude 판단 실패 [{} {}]: {}", coin, timeframe, e.getMessage());
            return TradeDecision.builder()
                    .action(Trade.TradeAction.HOLD)
                    .confidence(0)
                    .reason("Claude 판단 실패: " + e.getMessage())
                    .rawResponse("오류: " + e.getMessage())
                    .amount(0.0)
                    .coin(coin)
                    .timeframe(timeframe)
                    .build();
        }
    }

    /**
     * 프롬프트 + Claude 응답을 합쳐서 분석 기록 생성
     */
    private String buildAnalysisRecord(String prompt, String response) {
        return prompt + "\n\n════════ Claude 응답 ════════\n\n" + response;
    }

    public TradeDecision decide(MarketIndicators indicators, double balance) {
        return decide(indicators, balance, "ETH", "1H", null);
    }

    private String buildPrompt(MarketIndicators m, double balance, String coin, String timeframe,
                                PolymarketOddsService.MarketOdds odds) {
        String patternStats = buildPatternStats(m, coin, timeframe);
        boolean isBtc = "BTC".equals(coin);
        boolean is15m = "15M".equals(timeframe);
        double currentPrice = isBtc ? m.getBtcPrice() : m.getEthPrice();
        double openPrice = is15m
                ? (isBtc ? m.getBtc15mOpen() : m.getEth15mOpen())
                : (isBtc ? m.getBtcHourOpen() : m.getEthHourOpen());
        double priceDiff = openPrice > 0 ? currentPrice - openPrice : 0;
        double pricePct = openPrice > 0 ? (priceDiff / openPrice) * 100 : 0;

        String timeframeDesc = is15m ? "15분" : "1시간";
        String windowDesc = is15m ? "15분 윈도우" : "정시(매시 정각)";

        // 캔들 경과 시간 계산
        long now = System.currentTimeMillis();
        int elapsedMin, totalMin;
        if (is15m) {
            long windowStart = (now / 900_000) * 900_000; // 15분 단위
            elapsedMin = (int)((now - windowStart) / 60_000);
            totalMin = 15;
        } else {
            long hourStart = (now / 3_600_000) * 3_600_000; // 1시간 단위
            elapsedMin = (int)((now - hourStart) / 60_000);
            totalMin = 60;
        }
        int remainMin = totalMin - elapsedMin;

        StringBuilder sb = new StringBuilder();

        // === 역할 정의 ===
        sb.append(String.format("너는 Polymarket '%s Up or Down - %s' 마켓 전문 트레이더야.\n\n", coin, timeframe));

        // === 판정 기준 (가장 중요) ===
        sb.append("=== ⚡ 판정 기준 (핵심) ===\n");
        sb.append(String.format("- %s 시작 시점의 시초가와 종료 시점의 종가를 비교\n", windowDesc));
        sb.append("- 종가 > 시초가 → UP WIN (YES 토큰 보유자 승리)\n");
        sb.append("- 종가 < 시초가 → DOWN WIN (NO 토큰 보유자 승리)\n");
        sb.append("- 현재가는 참고용. 판정은 오직 시초가 vs 종가!\n\n");

        // === 현재 캔들 상태 (가장 중요한 실시간 신호) ===
        sb.append("=== 📍 현재 캔들 상태 (가장 중요) ===\n");
        sb.append(String.format("시초가: $%,.2f (%s 시작 기준)\n", openPrice, windowDesc));
        sb.append(String.format("현재가: $%,.2f\n", currentPrice));
        sb.append(String.format("경과: %d분 / %d분 (잔여 %d분)\n", elapsedMin, totalMin, remainMin));
        if (openPrice > 0) {
            String dir = priceDiff >= 0 ? "▲ UP 방향" : "▼ DOWN 방향";
            sb.append(String.format("현재 상태: %s (%+.4f%%, $%+.2f)\n", dir, pricePct, priceDiff));
            sb.append(String.format("→ 지금 종료되면: %s\n", priceDiff >= 0 ? "UP WIN" : "DOWN WIN"));
        }
        if (remainMin <= 5) {
            sb.append("⚠️ 캔들 종료 임박! 방향 반전 확률 매우 낮음. 현재 방향에 높은 가중치.\n");
        } else if (elapsedMin <= 3 && !is15m) {
            sb.append("⚠️ 캔들 초반! 방향 미확정. 변동성 높아 확신 낮게.\n");
        }
        sb.append("\n");

        // === 폴리마켓 오즈 (시장 컨센서스) ===
        if (odds != null) {
            double upPct = odds.upOdds() * 100;
            double downPct = odds.downOdds() * 100;
            sb.append("=== 🎲 시장 오즈 (다른 트레이더들의 예측) ===\n");
            sb.append(String.format("UP: %.1f¢ (시장이 UP 확률 %.1f%%로 봄)\n", upPct, upPct));
            sb.append(String.format("DOWN: %.1f¢ (시장이 DOWN 확률 %.1f%%로 봄)\n", downPct, downPct));
            if (upPct > 65) sb.append("→ 시장은 강하게 UP 예상. 역배팅(DOWN) 시 고수익 가능.\n");
            else if (downPct > 65) sb.append("→ 시장은 강하게 DOWN 예상. 역배팅(UP) 시 고수익 가능.\n");
            else sb.append("→ 시장은 비교적 중립. 확신 있을 때만 진입.\n");
            sb.append(String.format("출처: %s\n", odds.slug()));
            sb.append("\n");
        }

        // === 시장 지표 (coin별 맞춤) ===
        sb.append(String.format("=== 📊 %s 시장 지표 ===\n", coin));
        if (isBtc) {
            sb.append(String.format("BTC 1H: %+.2f%% | 4H: %+.2f%% | 24H: %+.2f%%\n",
                    m.getBtcChange1h(), m.getBtcChange4h(), m.getBtcChange24h()));
            sb.append(String.format("ETH 1H: %+.2f%% (연관 지표)\n", m.getEthChange1h()));
        } else {
            sb.append(String.format("ETH 1H: %+.2f%% | 4H: %+.2f%% | 24H: %+.2f%%\n",
                    m.getEthChange1h(), m.getEthChange4h(), m.getEthChange24h()));
            sb.append(String.format("BTC 1H: %+.2f%% (연관 지표)\n", m.getBtcChange1h()));
        }
        sb.append(String.format("추세: %s\n\n", m.getTrend()));

        // === 선물 시장 ===
        sb.append("=== 📈 선물 시장 ===\n");
        sb.append(String.format("펀딩비: %+.4f%%", m.getFundingRate()));
        if (Math.abs(m.getFundingRate()) > 0.05) {
            sb.append(m.getFundingRate() > 0 ? " ⚠️ 롱 과열 → 단기 하락 가능" : " ⚠️ 숏 과열 → 단기 상승 가능");
        }
        sb.append("\n");
        double oiChange = is15m ? m.getOpenInterestChange5m() : m.getOpenInterestChange();
        String oiLabel = is15m ? "OI 변화(5분)" : "OI 변화(30분)";
        sb.append(String.format("%s: %+.2f%%", oiLabel, oiChange));
        if (Math.abs(oiChange) > 3) {
            sb.append(oiChange > 0 ? " (신규 포지션 유입)" : " (청산 진행)");
        }
        sb.append("\n");
        // 롱숏비율
        double lsr = m.getLongShortRatio();
        if (lsr > 0) {
            sb.append(String.format("롱숏비율: %.2f", lsr));
            if (lsr > 1.5) sb.append(" ⚠️ 롱 과밀집 → 숏스퀴즈 or 롱 청산 리스크");
            else if (lsr < 0.67) sb.append(" ⚠️ 숏 과밀집 → 롱스퀴즈 or 숏 청산 리스크");
            else sb.append(" (중립)");
            sb.append("\n");
        }
        sb.append("\n");

        // === 기술적 지표 (타임프레임 맞춤) ===
        double rsi = is15m ? m.getRsi15m() : m.getRsi();
        double macdHist = is15m ? m.getMacd15m() : m.getMacd();
        double macdLine = is15m ? m.getMacdLine15m() : m.getMacdLine();
        double macdSignalVal = is15m ? m.getMacdSignal15m() : m.getMacdSignal();
        String rsiInterval = is15m ? "15M" : "1H";

        sb.append(String.format("=== 🔧 기술적 지표 (%s 캔들 기반) ===\n", rsiInterval));
        sb.append(String.format("RSI(14): %.1f", rsi));
        if (rsi > 75) sb.append(" ⚠️ 강한 과매수 → 하락 전환 가능");
        else if (rsi > 65) sb.append(" 과매수 근접");
        else if (rsi < 25) sb.append(" ⚠️ 강한 과매도 → 반등 가능");
        else if (rsi < 35) sb.append(" 과매도 근접");
        else sb.append(" 중립");
        sb.append("\n");
        sb.append(String.format("MACD: %.2f (시그널: %.2f, 히스토그램: %+.2f)", macdLine, macdSignalVal, macdHist));
        if (macdHist > 0 && macdLine > macdSignalVal) sb.append(" 강세 확대");
        else if (macdHist > 0) sb.append(" 강세 (약화 중)");
        else if (macdHist < 0 && macdLine < macdSignalVal) sb.append(" 약세 확대");
        else if (macdHist < 0) sb.append(" 약세 (반등 조짐)");
        sb.append("\n\n");

        // === 심리 ===
        sb.append(String.format("공포탐욕: %d (%s)\n\n", m.getFearGreedIndex(), m.getFearGreedLabel()));

        // === 15M 특화 컨텍스트 ===
        if (is15m) {
            sb.append("=== ⏱ 15M 특화 ===\n");
            sb.append("- 15분은 노이즈 극심. 현재 캔들 방향(시초가 vs 현재가)이 가장 강력한 신호\n");
            sb.append("- 캔들 후반부(10분+)에서 방향 반전 확률은 낮음\n");
            sb.append("- 펀딩비는 15분에 큰 영향 없음. OI 5분 변화 + RSI + 현재 방향 위주로 판단\n");
            sb.append("- 확신 낮으면 반드시 HOLD\n\n");
        }

        // === 과거 성적 ===
        sb.append(String.format("=== 📋 과거 성적 [%s %s] ===\n", coin, timeframe));
        sb.append(patternStats);
        sb.append("\n\n");

        // === 🧠 3계층 누적 학습 ===
        sb.append(buildLearningBlock(coin, timeframe));
        sb.append("\n");

        // === 판단 요청 ===
        sb.append(String.format("잔액: $%.2f\n\n", balance));
        sb.append("=== 판단 규칙 ===\n");
        sb.append("1. 시초가 vs 현재가 방향이 1차 신호 (모멘텀)\n");
        sb.append(String.format("2. 경과 시간 고려: %d/%d분 경과 → %s\n",
                elapsedMin, totalMin,
                remainMin <= 5 ? "종료 임박, 현재 방향 유지 가능성 높음" :
                elapsedMin <= 5 ? "초반, 불확실성 높음" : "중반, 추세 확인 중"));
        sb.append("3. RSI 극단(>75/<25) + 펀딩비 과열 = 역전 신호\n");
        sb.append("4. 시장 오즈가 60% 이상 편향 + 위 역전 신호 = 역배팅 기회\n");
        sb.append("5. 신호 불명확하면 무조건 HOLD\n\n");
        sb.append("다음 형식으로만 답해:\n");
        sb.append("ACTION: UP 또는 DOWN 또는 HOLD\n");
        sb.append("CONFIDENCE: 50~95 (아래 기준)\n");
        sb.append("  90~95: 다수 지표 강력 일치 + 현재 캔들 방향 확인 + 캔들 후반부\n");
        sb.append("  80~89: 주요 신호 2~3개 일치\n");
        sb.append("  70~79: 방향은 보이나 혼재\n");
        sb.append("  60~69: 약한 신호\n");
        sb.append("  50~59: 불확실 → HOLD\n");
        sb.append("AMOUNT: 배팅금액\n");
        sb.append("REASON: 한국어 핵심 근거 2~3줄\n");

        return sb.toString();
    }

    private String buildPatternStats(MarketIndicators m, String coin, String timeframe) {
        List<Trade> recentTrades = tradeRepository.findTop50ByCoinAndTimeframeForStats(coin, timeframe);
        if (recentTrades.isEmpty()) return "첫 배팅 (데이터 없음)";
        long wins = recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        long losses = recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.LOSE).count();
        long resolved = wins + losses;
        if (resolved == 0) return "결과 확정 없음";
        double winRate = (double) wins / resolved * 100;
        StringBuilder sb = new StringBuilder(String.format("[%s %s] 승률: %.1f%% (%d건 중 %d승 %d패)\n",
                coin, timeframe, winRate, resolved, wins, losses));
        // 최근 5건 결과 시퀀스
        sb.append("최근 5건: ");
        recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN || t.getResult() == Trade.TradeResult.LOSE).limit(5)
                .forEach(t -> {
                    String icon = t.getResult() == Trade.TradeResult.WIN ? "✅" : "❌";
                    String dir = t.getAction() == Trade.TradeAction.BUY_YES ? "U" : "D";
                    sb.append(icon).append(dir).append(" ");
                });
        sb.append("\n");
        // UP/DOWN별 승률 분석
        long upWins = recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN && t.getAction() == Trade.TradeAction.BUY_YES).count();
        long upTotal = recentTrades.stream().filter(t -> t.getResult() != Trade.TradeResult.PENDING && t.getAction() == Trade.TradeAction.BUY_YES).count();
        long downWins = recentTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN && t.getAction() == Trade.TradeAction.BUY_NO).count();
        long downTotal = recentTrades.stream().filter(t -> t.getResult() != Trade.TradeResult.PENDING && t.getAction() == Trade.TradeAction.BUY_NO).count();
        if (upTotal > 0) sb.append(String.format("UP 배팅 승률: %.0f%% (%d/%d)\n", (double)upWins/upTotal*100, upWins, upTotal));
        if (downTotal > 0) sb.append(String.format("DOWN 배팅 승률: %.0f%% (%d/%d)", (double)downWins/downTotal*100, downWins, downTotal));
        return sb.toString();
    }

    // ===================================================================
    //  3계층 누적 학습 블록
    //  Level 1: 조건별 승률 매트릭스 (코드 계산, 항상 정확)
    //  Level 2: AI 압축 교훈 (반성 누적 → 규칙화)
    //  Level 3: 최근 반성 2건 (생생한 최신 컨텍스트)
    // ===================================================================
    private String buildLearningBlock(String coin, String timeframe) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 🧠 누적 학습 데이터 (과거 경험에서 추출) ===\n");

        // ── Level 1: 조건별 승률 매트릭스 ──
        sb.append("── [L1] 조건별 승률 (DB 통계) ──\n");
        sb.append(buildConditionalStats(coin));

        // ── Level 2: AI 압축 교훈 ──
        List<TradingLesson> lessons = lessonService.getActiveLessons();
        if (!lessons.isEmpty()) {
            sb.append("── [L2] AI 압축 교훈 (반성 누적 → 규칙화) ──\n");
            for (TradingLesson l : lessons) {
                String imp = l.getImportance() >= 0.8 ? "🔴" : l.getImportance() >= 0.5 ? "🟡" : "⚪";
                sb.append(String.format("%s [%s] %s (근거 %d건)\n",
                        imp, l.getCategory(), l.getLesson(), l.getEvidenceCount()));
            }
            sb.append("\n");
        } else {
            sb.append("── [L2] 교훈 아직 없음 (반성 5건 누적 후 생성) ──\n\n");
        }

        // ── Level 3: 최근 반성 2건 ──
        List<Trade> recentReflected = tradeRepository.findRecentReflectedTrades(2);
        if (!recentReflected.isEmpty()) {
            sb.append("── [L3] 최근 반성 (직전 실수 방지) ──\n");
            for (Trade t : recentReflected) {
                String dir = t.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN";
                String result = t.getResult() == Trade.TradeResult.WIN ? "✅WIN" : "❌LOSE";
                sb.append(String.format("[%s %s] %s배팅 → %s: %s\n",
                        t.getCoin(), t.getTimeframe() != null ? t.getTimeframe() : "1H",
                        dir, result, t.getReflection()));
            }
            sb.append("\n");
        }

        // ── 연패/연승 경고 ──
        sb.append(buildStreakWarning(coin));

        return sb.toString();
    }

    /**
     * Level 1: 조건별 승률 매트릭스
     * 펀딩비 방향, 추세, UP/DOWN별 등 조건 조합 승률을 코드로 계산
     */
    private String buildConditionalStats(String coin) {
        StringBuilder sb = new StringBuilder();

        // 펀딩비 양수 승률
        Long pfWins = tradeRepository.countWinsWithPositiveFundingByCoin(coin);
        Long pfTotal = tradeRepository.countResolvedWithPositiveFundingByCoin(coin);
        if (pfTotal != null && pfTotal >= 3) {
            sb.append(String.format("  펀딩비 양수(롱과열) 시: 승률 %.0f%% (%d/%d건)\n",
                    (double) pfWins / pfTotal * 100, pfWins, pfTotal));
        }

        // 펀딩비 음수 승률
        Long nfWins = tradeRepository.countWinsWithNegativeFundingByCoin(coin);
        Long nfTotal = tradeRepository.countResolvedWithNegativeFundingByCoin(coin);
        if (nfTotal != null && nfTotal >= 3) {
            sb.append(String.format("  펀딩비 음수(숏과열) 시: 승률 %.0f%% (%d/%d건)\n",
                    (double) nfWins / nfTotal * 100, nfWins, nfTotal));
        }

        // 추세별 승률
        for (String trend : List.of("UPTREND", "DOWNTREND", "SIDEWAYS")) {
            Long tw = tradeRepository.countWinsByTrendAndCoin(trend, coin);
            Long tt = tradeRepository.countResolvedByTrendAndCoin(trend, coin);
            if (tt != null && tt >= 3) {
                String label = switch (trend) {
                    case "UPTREND" -> "상승추세";
                    case "DOWNTREND" -> "하락추세";
                    default -> "횡보장";
                };
                sb.append(String.format("  %s 시: 승률 %.0f%% (%d/%d건)\n",
                        label, (double) tw / tt * 100, tw, tt));
            }
        }

        if (sb.isEmpty()) {
            sb.append("  아직 데이터 부족 (3건 이상 쌓이면 표시)\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 연패/연승 경고
     */
    private String buildStreakWarning(String coin) {
        List<Trade> recent = tradeRepository.findRecent10ResolvedByCoin(coin);
        if (recent.isEmpty()) return "";

        int streak = 0;
        Trade.TradeResult streakType = recent.get(0).getResult();
        for (Trade t : recent) {
            if (t.getResult() == streakType) streak++;
            else break;
        }

        StringBuilder sb = new StringBuilder();
        if (streakType == Trade.TradeResult.LOSE && streak >= 3) {
            sb.append(String.format("⚠️ %s %d연패 중! 보수적 접근 권장. 확신도 기준 +10 상향.\n", coin, streak));
        } else if (streakType == Trade.TradeResult.WIN && streak >= 4) {
            sb.append(String.format("🔥 %s %d연승 중! 과신 주의. 평소 기준 유지.\n", coin, streak));
        }

        // 최근 10건 승률
        long w10 = recent.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        if (recent.size() >= 5) {
            sb.append(String.format("최근 %d건 승률: %.0f%%\n", recent.size(), (double) w10 / recent.size() * 100));
        }
        return sb.toString();
    }

    private TradeDecision parseDecision(String response, MarketIndicators indicators, String coin, String timeframe) {
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
            // 확신도 55% 미만이면 HOLD (최소 기준만 유지, EV 필터가 주 역할)
            if (confidence < 55) {
                tradeAction = Trade.TradeAction.HOLD;
                reason = "[확신도 부족 " + confidence + "%] " + reason;
            }
            return TradeDecision.builder()
                    .action(tradeAction).confidence(confidence).amount(amount).reason(reason)
                    .marketId(coin.toLowerCase() + "-" + timeframe.toLowerCase() + "-updown")
                    .marketTitle(coin + " Up or Down - " + timeframe)
                    .coin(coin).timeframe(timeframe).build();
        } catch (Exception e) {
            log.error("파싱 실패: {}", response);
            return TradeDecision.builder().action(Trade.TradeAction.HOLD).confidence(0)
                    .reason("파싱 실패").amount(0.0).build();
        }
    }

    // ===================================================================
    //  모멘텀 전략: 반전 위험 체크 전용
    //  방향은 가격이 결정, Claude는 "거부권"만 행사
    // ===================================================================

    public record ReversalCheck(boolean shouldProceed, String reason, int reversalRisk) {}

    public ReversalCheck checkReversal(MarketIndicators indicators, String coin, String timeframe,
                                        String direction, double pricePct, int elapsedMin, int remainMin,
                                        PolymarketOddsService.MarketOdds odds) {
        try {
            String prompt = buildReversalPrompt(indicators, coin, timeframe, direction, pricePct,
                    elapsedMin, remainMin, odds);
            String response = callClaude(prompt, modelLight, 150); // Haiku + 짧은 응답
            return parseReversalResponse(response);
        } catch (Exception e) {
            log.error("반전 체크 실패: {}", e.getMessage());
            return new ReversalCheck(false, "API 오류: " + e.getMessage(), 100);
        }
    }

    private String buildReversalPrompt(MarketIndicators m, String coin, String timeframe,
                                        String direction, double pricePct, int elapsedMin, int remainMin,
                                        PolymarketOddsService.MarketOdds odds) {
        boolean isBtc = "BTC".equals(coin);
        boolean is15m = "15M".equals(timeframe);
        double rsi = is15m ? m.getRsi15m() : m.getRsi();
        double oiChange = is15m ? m.getOpenInterestChange5m() : m.getOpenInterestChange();
        double lsr = m.getLongShortRatio();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Polymarket %s %s 마켓. 캔들 %d분 경과, 잔여 %d분.\n\n", coin, timeframe, elapsedMin, remainMin));
        sb.append(String.format("현재 방향: %s (%+.3f%%)\n", direction, pricePct));
        sb.append("→ 이 방향대로 모멘텀 배팅 예정. 반전 위험만 체크해줘.\n\n");

        sb.append("=== 반전 신호 체크리스트 ===\n");
        sb.append(String.format("RSI(14): %.1f", rsi));
        if ("UP".equals(direction) && rsi > 75) sb.append(" ⚠️ 과매수 → 하락 반전 위험");
        else if ("DOWN".equals(direction) && rsi < 25) sb.append(" ⚠️ 과매도 → 상승 반전 위험");
        else sb.append(" (안전)");
        sb.append("\n");

        sb.append(String.format("펀딩비: %+.4f%%", m.getFundingRate()));
        if ("UP".equals(direction) && m.getFundingRate() > 0.05) sb.append(" ⚠️ 롱 과열");
        else if ("DOWN".equals(direction) && m.getFundingRate() < -0.05) sb.append(" ⚠️ 숏 과열");
        else sb.append(" (안전)");
        sb.append("\n");

        sb.append(String.format("OI 변화: %+.2f%%", oiChange));
        if (Math.abs(oiChange) > 5) sb.append(" ⚠️ 대량 청산 가능");
        else sb.append(" (안전)");
        sb.append("\n");

        if (lsr > 0) {
            sb.append(String.format("롱숏비율: %.2f", lsr));
            if ("UP".equals(direction) && lsr > 2.0) sb.append(" ⚠️ 롱 과밀집");
            else if ("DOWN".equals(direction) && lsr < 0.5) sb.append(" ⚠️ 숏 과밀집");
            else sb.append(" (안전)");
            sb.append("\n");
        }

        if (odds != null) {
            double dirOdds = "UP".equals(direction) ? odds.upOdds() : odds.downOdds();
            sb.append(String.format("\n시장 오즈: %s %.0f%%\n", direction, dirOdds * 100));
        }

        sb.append("\n=== 판단 ===\n");
        sb.append("반전 위험이 낮으면 PROCEED, 높으면 VETO.\n");
        sb.append("VETO는 2개 이상 위험 신호가 동시에 발생할 때만.\n");
        sb.append("형식:\n");
        sb.append("DECISION: PROCEED 또는 VETO\n");
        sb.append("RISK: 0~100 (반전 위험도)\n");
        sb.append("REASON: 한국어 한 줄\n");

        return sb.toString();
    }

    private ReversalCheck parseReversalResponse(String response) {
        boolean proceed = true;
        int risk = 30;
        String reason = response;

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("DECISION:")) {
                proceed = line.toUpperCase().contains("PROCEED");
            } else if (line.startsWith("RISK:")) {
                try { risk = Integer.parseInt(line.replace("RISK:", "").trim()); } catch (Exception ignored) {}
            } else if (line.startsWith("REASON:")) {
                reason = line.replace("REASON:", "").trim();
            }
        }

        return new ReversalCheck(proceed, reason, risk);
    }

    // ===================================================================
    //  ⭐ V5: OddsGapScanner 전용 최종 거부권
    //  수학이 "갭 있다"고 판단 → Claude가 "진짜 배팅해도 되나?" 최종 체크
    //  Haiku + 100토큰 = 빠르고 저렴
    // ===================================================================

    public record GapVeto(boolean shouldProceed, String reason) {}

    public GapVeto vetoGapTrade(String coin, String timeframe, String direction,
                                 double priceDiffPct, double gap, double estimatedProb,
                                 double marketOdds, double ev, double momentumScore,
                                 String gapType) {
        try {
            String prompt = buildGapVetoPrompt(coin, timeframe, direction, priceDiffPct,
                    gap, estimatedProb, marketOdds, ev, momentumScore, gapType);
            String response = callClaude(prompt, modelLight, 100);
            return parseGapVetoResponse(response);
        } catch (Exception e) {
            log.warn("갭 거부권 체크 실패 (통과 처리): {}", e.getMessage());
            return new GapVeto(true, "API오류-통과처리"); // 실패 시 수학 판단 존중
        }
    }

    private String buildGapVetoPrompt(String coin, String timeframe, String direction,
                                       double priceDiffPct, double gap, double estimatedProb,
                                       double marketOdds, double ev, double momentumScore,
                                       String gapType) {
        boolean is15m = "15M".equals(timeframe);

        // 캔들 경과 시간
        long now = System.currentTimeMillis();
        int elapsedMin, totalMin;
        if (is15m) {
            long windowStart = (now / 900_000) * 900_000;
            elapsedMin = (int)((now - windowStart) / 60_000);
            totalMin = 15;
        } else {
            long hourStart = (now / 3_600_000) * 3_600_000;
            elapsedMin = (int)((now - hourStart) / 60_000);
            totalMin = 60;
        }
        int remainMin = totalMin - elapsedMin;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s %s] %s 배팅 최종 확인.\n\n", coin, timeframe, gapType));

        sb.append("=== 수학 엔진 산출 ===\n");
        sb.append(String.format("방향: %s | 가격변동: %+.2f%%\n", direction, priceDiffPct));
        sb.append(String.format("추정확률: %.0f%% vs 시장오즈: %.0f%% → 갭: %.1f%%\n",
                estimatedProb * 100, marketOdds * 100, gap * 100));
        sb.append(String.format("EV: %+.1f%% | 모멘텀일관성: %.0f%%\n", ev * 100, Math.abs(momentumScore) * 100));
        sb.append(String.format("캔들: %d/%d분 경과 (잔여 %d분)\n\n", elapsedMin, totalMin, remainMin));

        // 최근 동일 코인 트레이드 결과 (Claude가 패턴 파악용)
        List<Trade> recent = tradeRepository.findRecent10ResolvedByCoin(coin);
        if (!recent.isEmpty()) {
            sb.append("=== 최근 결과 ===\n");
            long wins = recent.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
            sb.append(String.format("최근 %d건: %d승 %d패 (%.0f%%)\n",
                    recent.size(), wins, recent.size() - wins,
                    (double) wins / recent.size() * 100));

            // 최근 3건 상세
            recent.stream().limit(3).forEach(t -> {
                String dir = t.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN";
                String result = t.getResult() == Trade.TradeResult.WIN ? "✅" : "❌";
                sb.append(String.format("  %s %s $%.2f → %s\n", result, dir,
                        t.getBetAmount(), t.getResult()));
            });
            sb.append("\n");
        }

        // AI 교훈 (Level 2)
        List<TradingLesson> lessons = lessonService.getActiveLessons();
        if (!lessons.isEmpty()) {
            sb.append("=== 학습된 교훈 ===\n");
            lessons.stream().limit(5).forEach(l ->
                    sb.append(String.format("- [%s] %s\n", l.getCategory(), l.getLesson())));
            sb.append("\n");
        }

        sb.append("=== 판단 ===\n");
        sb.append("이 배팅을 실행해도 되는가? 아래 기준으로:\n");
        sb.append("VETO 조건 (하나라도 해당하면 VETO):\n");
        sb.append("- 가격이 거의 안 움직였는데 배팅하려는 경우 (횡보장)\n");
        sb.append("- 최근 같은 패턴으로 연패 중인 경우\n");
        sb.append("- 캔들 초반인데 확신 부족한 경우\n");
        sb.append("- 교훈에서 명확히 경고한 패턴인 경우\n");
        sb.append("- 역방향 배팅인데 반전 근거가 약한 경우\n");
        sb.append("그 외에는 GO.\n\n");
        sb.append("형식 (한 줄씩):\n");
        sb.append("DECISION: GO 또는 VETO\n");
        sb.append("REASON: 한국어 한 줄\n");

        return sb.toString();
    }

    private GapVeto parseGapVetoResponse(String response) {
        boolean proceed = true;
        String reason = response;

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("DECISION:")) {
                proceed = line.toUpperCase().contains("GO");
            } else if (line.startsWith("REASON:")) {
                reason = line.replace("REASON:", "").trim();
            }
        }

        return new GapVeto(proceed, reason);
    }

    private String callClaude(String prompt) throws Exception {
        return callClaude(prompt, model, 300);
    }

    private String callClaude(String prompt, String useModel, int maxTokens) throws Exception {
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "user");
        messageNode.put("content", prompt);
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(messageNode);
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", useModel);
        requestNode.put("max_tokens", maxTokens);
        requestNode.set("messages", messages);
        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(requestNode), MediaType.get("application/json")))
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
