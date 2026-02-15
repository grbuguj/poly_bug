package com.example.poly_bug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 바이낸스 1분봉 패턴 분석
 * 목적: "N분 시점의 가격 방향이 최종 결과(종가)와 일치하는 확률" 계산
 *
 * 예) 72시간 분석 → 72개 1시간 구간
 *   각 구간에서 36분 시점 가격이 시작가보다 위 → 종가도 시작가보다 위? → 일치하면 correct
 *   36분의 accuracy = correct / 72 = "36분 시점 방향이 최종 답이 될 확률"
 */
@Slf4j
@Service
public class TimingAnalysisService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TriggerConfigService triggerConfigService;

    public TimingAnalysisService(TriggerConfigService triggerConfigService) {
        this.triggerConfigService = triggerConfigService;
    }

    /**
     * BTC/ETH 패턴 분석 (최근 N시간)
     */
    public String analyzeOptimalTiming(String coin, int hours) throws Exception {
        String symbol = coin + "USDT";

        log.info("📊 {} 최근 {}시간 패턴 분석 시작", coin, hours);

        // 1. 다회 호출로 전체 1분봉 수집
        List<Candle> allCandles = fetchAllCandles(symbol, hours);

        if (allCandles.size() < 120) {
            return "데이터 부족 (수집: " + allCandles.size() + "개, 최소 120분 필요)";
        }

        // 2. 정시(:00) 기준으로 1시간 구간 분할
        List<List<Candle>> hourBlocks = splitByExactHour(allCandles);

        if (hourBlocks.size() < 3) {
            return "완전한 1시간 구간 부족 (구간: " + hourBlocks.size() + "개)";
        }

        // 3. 각 분별 통계 계산
        MinuteStats[] stats = new MinuteStats[60];
        for (int i = 0; i < 60; i++) stats[i] = new MinuteStats();

        for (List<Candle> block : hourBlocks) {
            analyzeOneHour(block, stats);
        }

        // 4. 최적 트리거 2개 선택 (탐색 + 확인)
        int[] optimal = findOptimalTriggers(stats);

        // 5. 트리거 자동 업데이트
        double[] accuracies = new double[2];
        for (int i = 0; i < 2; i++) {
            MinuteStats s = stats[optimal[i]];
            accuracies[i] = s.count > 0 ? (double) s.correct / s.count : 0;
        }
        triggerConfigService.updateFromAnalysis(coin, optimal, accuracies, hours + "시간 분석");

        // 6. 결과 포맷팅
        return formatReport(coin, hours, stats, optimal, hourBlocks.size(), allCandles.size());
    }

    // ========== 다회 API 호출로 전체 데이터 수집 ==========

    /**
     * 바이낸스 1분봉 데이터 다회 수집 (1회 limit=1000)
     * 72시간 = 4320개 -> 5회 호출
     */
    private List<Candle> fetchAllCandles(String symbol, int hours) throws Exception {
        int totalNeeded = hours * 60;
        long now = System.currentTimeMillis();
        long startTime = now - (long) hours * 60 * 60 * 1000;

        List<Candle> allCandles = new ArrayList<>();
        long cursor = startTime;

        while (allCandles.size() < totalNeeded && cursor < now) {
            List<Candle> batch = fetchBatch(symbol, cursor, 1000);
            if (batch.isEmpty()) break;

            allCandles.addAll(batch);
            // 다음 배치 시작 = 마지막 캔들 시간 + 1분
            cursor = batch.get(batch.size() - 1).time + 60_000;

            log.info("  수집 진행: {}개 / {}개 필요", allCandles.size(), totalNeeded);

            // API 레이트 리밋 방지
            Thread.sleep(100);
        }

        // 중복 제거 (timestamp 기준)
        TreeMap<Long, Candle> dedup = new TreeMap<>();
        for (Candle c : allCandles) dedup.put(c.time, c);
        List<Candle> result = new ArrayList<>(dedup.values());

        log.info("✅ 총 {}개 캔들 수집 완료 (요청 {}시간)", result.size(), hours);
        return result;
    }

    private List<Candle> fetchBatch(String symbol, long startTime, int limit) throws Exception {
        String url = String.format(
                "https://api.binance.com/api/v3/klines?symbol=%s&interval=1m&startTime=%d&limit=%d",
                symbol, startTime, limit
        );

        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답");
            JsonNode data = objectMapper.readTree(res.body().string());

            List<Candle> candles = new ArrayList<>();
            for (JsonNode row : data) {
                candles.add(new Candle(
                        row.get(0).asLong(),   // open time
                        row.get(1).asDouble(),  // open
                        row.get(4).asDouble()   // close
                ));
            }
            return candles;
        }
    }

    // ========== 정시(:00) 기준 1시간 구간 분할 ==========

    /**
     * timestamp를 기반으로 정확히 :00~:59 구간으로 분할
     * 불완전한 구간(60개 미만)은 버림
     */
    private List<List<Candle>> splitByExactHour(List<Candle> candles) {
        // timestamp -> 해당 시간의 정시(밀리초) 매핑
        TreeMap<Long, List<Candle>> hourMap = new TreeMap<>();

        for (Candle c : candles) {
            // 정시 기준 = timestamp에서 분/초 제거
            long hourStart = c.time - (c.time % 3_600_000);
            hourMap.computeIfAbsent(hourStart, k -> new ArrayList<>()).add(c);
        }

        List<List<Candle>> completeHours = new ArrayList<>();
        for (Map.Entry<Long, List<Candle>> entry : hourMap.entrySet()) {
            List<Candle> block = entry.getValue();
            if (block.size() == 60) {
                // 분 순서대로 정렬
                block.sort(Comparator.comparingLong(c -> c.time));
                completeHours.add(block);
            }
        }

        log.info("✅ 완전한 1시간 구간: {}개 (불완전 구간 {}개 버림)",
                completeHours.size(), hourMap.size() - completeHours.size());
        return completeHours;
    }

    // ========== 분석 ==========

    /**
     * 1시간 구간 분석
     * basePrice = :00분 종가 (시작가)
     * finalPrice = :59분 종가 (최종가)
     * N분 시점에서 가격이 시작가보다 위 -> 최종가도 시작가보다 위? -> 일치하면 correct
     */
    private void analyzeOneHour(List<Candle> hour, MinuteStats[] stats) {
        double basePrice = hour.get(0).close;
        double finalPrice = hour.get(59).close;

        // 시작가 == 종가 (변동 없음) -> 분석 의미 없으므로 스킵
        if (Math.abs(finalPrice - basePrice) < 0.01) return;

        boolean actualUp = finalPrice > basePrice;

        for (int minute = 0; minute < 60; minute++) {
            double currentPrice = hour.get(minute).close;

            // 시작가와 동일하면 방향 판단 불가 -> 스킵
            if (Math.abs(currentPrice - basePrice) < 0.01) continue;

            boolean predictUp = currentPrice > basePrice;
            boolean correct = (predictUp == actualUp);

            double volatility = Math.abs(currentPrice - basePrice) / basePrice * 100;
            double remaining = Math.abs(finalPrice - currentPrice) / basePrice * 100;

            boolean reversed = (predictUp != actualUp); // 이 시점 방향이 최종과 반대

            stats[minute].addSample(correct, volatility, remaining, reversed);
        }
    }

    /**
     * 최적 트리거 2개 선택 (35~57분 범위, 최소 간격 8분)
     * 1차(탐색): 35~45분대 최고점, 2차(확인): 46~57분대 최고점
     */
    private int[] findOptimalTriggers(MinuteStats[] stats) {
        // 탐색 구간 (35~45) / 확인 구간 (46~57) 분리
        int bestEarly = -1; double bestEarlyScore = -1;
        int bestLate = -1;  double bestLateScore = -1;

        for (int minute = 35; minute <= 57; minute++) {
            MinuteStats stat = stats[minute];
            if (stat.count < 5) continue;

            double accuracy = (double) stat.correct / stat.count;
            double reversalRate = (double) stat.reversed / stat.count;
            double avgVol = stat.totalVolatility / stat.count;
            double avgRem = stat.totalRemaining / stat.count;

            double score =
                    accuracy * 0.40 +
                    (1 - reversalRate) * 0.30 +
                    (1 - Math.min(avgVol / 2, 1)) * 0.20 +
                    Math.min(avgRem, 1) * 0.10;

            if (minute <= 45) {
                if (score > bestEarlyScore) { bestEarlyScore = score; bestEarly = minute; }
            } else {
                if (score > bestLateScore) { bestLateScore = score; bestLate = minute; }
            }
        }

        // 기본값 보장
        if (bestEarly < 0) bestEarly = 38;
        if (bestLate < 0) bestLate = 52;

        // 최소 간격 8분 보장
        if (bestLate - bestEarly < 8) bestLate = Math.min(bestEarly + 8, 57);

        return new int[]{bestEarly, bestLate};
    }

    // ========== 리포트 ==========

    private String formatReport(String coin, int hours, MinuteStats[] stats,
                                 int[] optimal, int hourCount, int totalCandles) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n📊 %s 패턴 분석 (최근 %d시간)\n", coin, hours));
        sb.append(String.format("   수집: %,d개 캔들 → 완전한 1시간 구간: %d개 (표본)\n\n", totalCandles, hourCount));
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(String.format("%-4s | %-7s | %-7s | %-7s | %-7s | %-6s | %s\n",
                "분", "일치율", "반전율", "변동%", "여지%", "점수", "표본"));
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        // 35~57분 전부 표시 (분 순서대로)
        for (int i = 35; i <= 57; i++) {
            MinuteStats stat = stats[i];
            if (stat.count == 0) continue;

            double accuracy = (double) stat.correct / stat.count;
            double reversalRate = (double) stat.reversed / stat.count;
            double avgVol = stat.totalVolatility / stat.count;
            double avgRem = stat.totalRemaining / stat.count;

            double score =
                    accuracy * 0.40 +
                    (1 - reversalRate) * 0.30 +
                    (1 - Math.min(avgVol / 2, 1)) * 0.20 +
                    Math.min(avgRem, 1) * 0.10;

            String mark = "";
            for (int opt : optimal) {
                if (i == opt) { mark = " ⭐"; break; }
            }

            sb.append(String.format("%02d분 | %5.1f%% | %5.1f%% | %5.2f%% | %5.2f%% | %5.1f | %d개%s\n",
                    i,
                    accuracy * 100,
                    reversalRate * 100,
                    avgVol,
                    avgRem,
                    score * 100,
                    stat.count,
                    mark
            ));
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("\n💡 추천 트리거 (2개):\n\n");

        String[] labels = {"탐색", "확인"};
        for (int i = 0; i < 2; i++) {
            int minute = optimal[i];
            MinuteStats stat = stats[minute];
            if (stat.count == 0) continue;

            double accuracy = (double) stat.correct / stat.count;
            double reversalRate = (double) stat.reversed / stat.count;

            double threshold;
            if (accuracy >= 0.72) threshold = 0.10;
            else if (accuracy >= 0.68) threshold = 0.12;
            else if (accuracy >= 0.65) threshold = 0.15;
            else if (accuracy >= 0.60) threshold = 0.18;
            else threshold = 0.20;

            sb.append(String.format("  %s: %02d분 (일치 %.1f%%, 반전 %.1f%%, 표본 %d개) → EV 임계값 %.0f%%\n",
                    labels[i], minute, accuracy * 100, reversalRate * 100, stat.count, threshold * 100));
        }

        sb.append("\n📌 용어 설명:\n");
        sb.append("  일치율 = N분 시점 방향이 최종 결과와 같은 비율 (높을수록 좋음)\n");
        sb.append("  반전율 = N분 이후 방향이 뒤집힌 비율 (낮을수록 좋음)\n");
        sb.append("  변동%  = 시작가 대비 N분 시점 가격 변화 크기\n");
        sb.append("  여지%  = N분 시점에서 종가까지 남은 변화 크기\n");
        sb.append("  점수   = 일치40% + 안정30% + 낮은변동20% + 여지10%\n");
        sb.append(String.format("  표본   = 분석된 1시간 구간 수 (%d개, 많을수록 신뢰↑)\n", hourCount));

        return sb.toString();
    }

    // ========== 15M 분석 ==========

    /**
     * BTC/ETH 15분봉 패턴 분석 (최근 N시간)
     * 15분 윈도우 내에서 각 분의 방향 일치율 분석
     */
    public String analyzeOptimalTiming15m(String coin, int hours) throws Exception {
        String symbol = "BTC".equals(coin) ? "BTCUSDT" : "ETHUSDT";
        String configKey = coin + "_15M";

        log.info("📊 {} 15M 최근 {}시간 패턴 분석 시작", coin, hours);

        // 1. 1분봉 수집
        List<Candle> allCandles = fetchAllCandles(symbol, hours);
        if (allCandles.size() < 30) {
            return "데이터 부족 (수집: " + allCandles.size() + "개)";
        }

        // 2. 15분 윈도우로 분할
        List<List<Candle>> windows = splitBy15Min(allCandles);
        if (windows.size() < 10) {
            return "완전한 15분 구간 부족 (구간: " + windows.size() + "개)";
        }

        // 3. 각 분별 통계 (0~14)
        MinuteStats[] stats = new MinuteStats[15];
        for (int i = 0; i < 15; i++) stats[i] = new MinuteStats();

        for (List<Candle> window : windows) {
            analyze15MinWindow(window, stats);
        }

        // 4. 최적 트리거 2개 선택
        int[] optimal = findOptimalTriggers15m(stats);

        // 5. 트리거 업데이트
        double[] accuracies = new double[2];
        for (int i = 0; i < 2; i++) {
            MinuteStats s = stats[optimal[i]];
            accuracies[i] = s.count > 0 ? (double) s.correct / s.count : 0;
        }
        triggerConfigService.updateFromAnalysis(configKey, optimal, accuracies, hours + "시간 15M분석");

        // 6. 리포트
        return formatReport15m(coin, hours, stats, optimal, windows.size(), allCandles.size());
    }

    /**
     * 15분 단위 윈도우로 분할
     * 완전한 15개(0~14분) 있는 윈도우만 사용
     */
    private List<List<Candle>> splitBy15Min(List<Candle> candles) {
        TreeMap<Long, List<Candle>> windowMap = new TreeMap<>();

        for (Candle c : candles) {
            // 15분 윈도우 시작 = timestamp에서 15분 단위로 내림
            long windowStart = c.time - (c.time % (15 * 60_000));
            windowMap.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(c);
        }

        List<List<Candle>> completeWindows = new ArrayList<>();
        for (Map.Entry<Long, List<Candle>> entry : windowMap.entrySet()) {
            List<Candle> block = entry.getValue();
            if (block.size() == 15) {
                block.sort(Comparator.comparingLong(c -> c.time));
                completeWindows.add(block);
            }
        }

        log.info("✅ 완전한 15분 구간: {}개 (불완전 {}개 버림)",
                completeWindows.size(), windowMap.size() - completeWindows.size());
        return completeWindows;
    }

    /**
     * 15분 윈도우 분석
     * basePrice = 0분 종가, finalPrice = 14분 종가
     */
    private void analyze15MinWindow(List<Candle> window, MinuteStats[] stats) {
        double basePrice = window.get(0).close;
        double finalPrice = window.get(14).close;

        if (Math.abs(finalPrice - basePrice) < 0.01) return;

        boolean actualUp = finalPrice > basePrice;

        for (int minute = 0; minute < 15; minute++) {
            double currentPrice = window.get(minute).close;
            if (Math.abs(currentPrice - basePrice) < 0.01) continue;

            boolean predictUp = currentPrice > basePrice;
            boolean correct = (predictUp == actualUp);
            double volatility = Math.abs(currentPrice - basePrice) / basePrice * 100;
            double remaining = Math.abs(finalPrice - currentPrice) / basePrice * 100;
            boolean reversed = (predictUp != actualUp);

            stats[minute].addSample(correct, volatility, remaining, reversed);
        }
    }

    /**
     * 15M 최적 트리거 2개 선택
     * 탐색: 2~7분, 확인: 8~13분 (윈도우 내 오프셋)
     */
    private int[] findOptimalTriggers15m(MinuteStats[] stats) {
        int bestEarly = -1; double bestEarlyScore = -1;
        int bestLate = -1;  double bestLateScore = -1;

        for (int minute = 2; minute <= 13; minute++) {
            MinuteStats stat = stats[minute];
            if (stat.count < 5) continue;

            double accuracy = (double) stat.correct / stat.count;
            double reversalRate = (double) stat.reversed / stat.count;
            double avgVol = stat.totalVolatility / stat.count;
            double avgRem = stat.totalRemaining / stat.count;

            double score =
                    accuracy * 0.40 +
                    (1 - reversalRate) * 0.30 +
                    (1 - Math.min(avgVol / 2, 1)) * 0.20 +
                    Math.min(avgRem, 1) * 0.10;

            if (minute <= 7) {
                if (score > bestEarlyScore) { bestEarlyScore = score; bestEarly = minute; }
            } else {
                if (score > bestLateScore) { bestLateScore = score; bestLate = minute; }
            }
        }

        if (bestEarly < 0) bestEarly = 3;
        if (bestLate < 0) bestLate = 10;
        if (bestLate - bestEarly < 3) bestLate = Math.min(bestEarly + 3, 13);

        return new int[]{bestEarly, bestLate};
    }

    private String formatReport15m(String coin, int hours, MinuteStats[] stats,
                                    int[] optimal, int windowCount, int totalCandles) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n📊 %s 15M 패턴 분석 (최근 %d시간)\n", coin, hours));
        sb.append(String.format("   수집: %,d개 캔들 → 완전한 15분 구간: %d개\n\n", totalCandles, windowCount));
        sb.append("분(오프셋) | 일치율 | 반전율 | 표본\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (int i = 0; i < 15; i++) {
            MinuteStats stat = stats[i];
            if (stat.count == 0) continue;
            double accuracy = (double) stat.correct / stat.count;
            double reversalRate = (double) stat.reversed / stat.count;
            String mark = "";
            for (int opt : optimal) { if (i == opt) { mark = " ⭐"; break; } }
            sb.append(String.format("+%02d분 | %5.1f%% | %5.1f%% | %d개%s\n",
                    i, accuracy * 100, reversalRate * 100, stat.count, mark));
        }

        sb.append(String.format("\n💡 추천 트리거: 탐색 +%d분, 확인 +%d분\n", optimal[0], optimal[1]));
        return sb.toString();
    }

    // ========== 데이터 클래스 ==========

    private record Candle(long time, double open, double close) {}

    private static class MinuteStats {
        int count = 0;
        int correct = 0;
        int reversed = 0;
        double totalVolatility = 0;
        double totalRemaining = 0;

        void addSample(boolean isCorrect, double vol, double rem, boolean rev) {
            count++;
            if (isCorrect) correct++;
            if (rev) reversed++;
            totalVolatility += vol;
            totalRemaining += rem;
        }
    }
}
