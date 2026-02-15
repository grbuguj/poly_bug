package com.example.poly_bug.controller;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.service.MarketDataService;
import com.example.poly_bug.service.PolymarketOddsService;
import com.example.poly_bug.service.TradingService;
import com.example.poly_bug.service.TriggerConfigService;
import com.example.poly_bug.service.BalanceService;
import com.example.poly_bug.service.LessonService;
import com.example.poly_bug.service.OddsGapScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final TradeRepository tradeRepository;
    private final TriggerConfigService triggerConfigService;
    private final TradingService tradingService;
    private final MarketDataService marketDataService;
    private final PolymarketOddsService oddsService;
    private final BalanceService balanceService;
    private final LessonService lessonService;
    private final OddsGapScanner oddsGapScanner;

    @GetMapping("/")
    public String dashboard(Model model) {
        return "dashboard";
    }

    // ===== 잔액 조회 =====
    @GetMapping("/balance")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> balance() {
        Map<String, Object> map = new HashMap<>();
        map.put("balance", balanceService.getBalance());
        map.put("initial", balanceService.getInitialBalance());
        map.put("profitPct", balanceService.getProfitPct());
        map.put("profitLoss", balanceService.getBalance() - balanceService.getInitialBalance());

        // 활성 배팅 (PENDING)
        List<Trade> pending = tradeRepository.findByResult(Trade.TradeResult.PENDING);
        double lockedBtc = pending.stream().filter(t -> "BTC".equals(t.getCoin())).mapToDouble(Trade::getBetAmount).sum();
        double lockedEth = pending.stream().filter(t -> "ETH".equals(t.getCoin())).mapToDouble(Trade::getBetAmount).sum();
        double lockedSol = pending.stream().filter(t -> "SOL".equals(t.getCoin())).mapToDouble(Trade::getBetAmount).sum();
        double lockedXrp = pending.stream().filter(t -> "XRP".equals(t.getCoin())).mapToDouble(Trade::getBetAmount).sum();
        map.put("lockedBtc", lockedBtc);
        map.put("lockedEth", lockedEth);
        map.put("lockedSol", lockedSol);
        map.put("lockedXrp", lockedXrp);
        map.put("pendingCount", pending.size());
        map.put("available", balanceService.getBalance());

        return ResponseEntity.ok(map);
    }

    // ===== 잔액 히스토리 (이퀄리티 커브용) =====
    @GetMapping("/balance/history")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> balanceHistory() {
        double initial = balanceService.getInitialBalance();
        List<Map<String, Object>> points = new java.util.ArrayList<>();

        // 시작점
        Map<String, Object> start = new HashMap<>();
        start.put("time", null);
        start.put("balance", initial);
        start.put("event", "START");
        points.add(start);

        // 모든 트레이드를 시간순으로
        List<Trade> allTrades = tradeRepository.findAll().stream()
                .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        double bal = initial;
        for (Trade t : allTrades) {
            // 배팅 시점 (차감)
            bal -= t.getBetAmount();
            Map<String, Object> betPoint = new HashMap<>();
            betPoint.put("time", t.getCreatedAt().toString());
            betPoint.put("balance", Math.round(bal * 100.0) / 100.0);
            betPoint.put("event", "BET");
            betPoint.put("coin", t.getCoin());
            betPoint.put("action", t.getAction().name());
            betPoint.put("amount", t.getBetAmount());
            points.add(betPoint);

            // 결과 시점 (WIN이면 수익 추가)
            if (t.getResult() == Trade.TradeResult.WIN) {
                double pnl = (t.getProfitLoss() != null) ? t.getProfitLoss() : 0;
                double payout = t.getBetAmount() + pnl;
                bal += payout;
                Map<String, Object> winPoint = new HashMap<>();
                winPoint.put("time", t.getResolvedAt() != null ? t.getResolvedAt().toString() : t.getCreatedAt().plusHours(1).toString());
                winPoint.put("balance", Math.round(bal * 100.0) / 100.0);
                winPoint.put("event", "WIN");
                winPoint.put("coin", t.getCoin());
                winPoint.put("pnl", t.getProfitLoss());
                points.add(winPoint);
            } else if (t.getResult() == Trade.TradeResult.LOSE) {
                Map<String, Object> losePoint = new HashMap<>();
                losePoint.put("time", t.getResolvedAt() != null ? t.getResolvedAt().toString() : t.getCreatedAt().plusHours(1).toString());
                losePoint.put("balance", Math.round(bal * 100.0) / 100.0);
                losePoint.put("event", "LOSE");
                losePoint.put("coin", t.getCoin());
                losePoint.put("pnl", t.getProfitLoss());
                points.add(losePoint);
            }
            // PENDING: 차감만 된 상태
        }

        return ResponseEntity.ok(points);
    }

    // ===== 즉시 1회 실행 (코인별, 타임프레임별) =====
    @PostMapping("/trade/run")
    @ResponseBody
    public ResponseEntity<Map<String, String>> runOnce(
            @RequestParam(defaultValue = "BTC") String coin,
            @RequestParam(defaultValue = "1H") String timeframe) {
        new Thread(() -> tradingService.executeCycle(coin, timeframe, -1)).start();
        return ResponseEntity.ok(Map.of("status", "started", "coin", coin, "timeframe", timeframe));
    }

    // ===== 결과 업데이트 =====
    @PostMapping("/trade/{id}/result")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateResult(
            @PathVariable Long id,
            @RequestParam String result,
            @RequestParam(required = false, defaultValue = "0") Double exitPrice) {
        Trade.TradeResult r = Trade.TradeResult.valueOf(result.toUpperCase());
        tradingService.updateTradeResult(id, r, exitPrice);
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    // ===== 현재 지표 조회 (coin 파라미터 지원) =====
    @GetMapping("/indicators")
    @ResponseBody
    public ResponseEntity<MarketIndicators> indicators(
            @RequestParam(required = false, defaultValue = "ETH") String coin) {
        return ResponseEntity.ok(marketDataService.collect(coin));
    }

    // ===== 통계 (coin + timeframe 파라미터 지원) — HOLD 제외, 실제 배팅만 =====
    @GetMapping("/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam(required = false, defaultValue = "") String coin,
            @RequestParam(required = false, defaultValue = "") String timeframe) {

        boolean filtered = !coin.isEmpty();
        boolean tfFiltered = !timeframe.isEmpty();

        Long resolved, wins;
        Double pnl;
        long totalBets;

        if (tfFiltered) {
            // timeframe 필터링: Java에서 처리 (1H일 때 null 레거시도 포함)
            List<Trade> allTrades = tradeRepository.findAll().stream()
                    .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                    .filter(t -> !filtered || coin.equals(t.getCoin()))
                    .filter(t -> timeframe.equals(t.getTimeframe())
                            || ("1H".equals(timeframe) && t.getTimeframe() == null))
                    .toList();
            totalBets = allTrades.size();
            resolved = allTrades.stream().filter(t -> t.getResult() != Trade.TradeResult.PENDING).count();
            wins = allTrades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
            pnl = allTrades.stream().filter(t -> t.getProfitLoss() != null).mapToDouble(Trade::getProfitLoss).sum();
        } else if (filtered) {
            resolved = tradeRepository.countResolvedByCoin(coin);
            wins = tradeRepository.countWinsByCoin(coin);
            pnl = tradeRepository.totalProfitLossByCoin(coin);
            totalBets = tradeRepository.countActualBetsByCoin(coin);
        } else {
            resolved = tradeRepository.countResolved();
            wins = tradeRepository.countWins();
            pnl = tradeRepository.totalProfitLoss();
            totalBets = tradeRepository.countActualBets();
        }

        Map<String, Object> map = new HashMap<>();
        map.put("total", totalBets);
        map.put("resolved", resolved);
        map.put("wins", wins);
        map.put("winRate", resolved > 0 ? String.format("%.1f", (double) wins / resolved * 100) : "0");
        map.put("pnl", pnl != null ? pnl : 0.0);

        // 패턴 통계
        Map<String, String> patterns = new HashMap<>();
        long pfWins = tradeRepository.countWinsWithPositiveFunding();
        long pfTotal = tradeRepository.countResolvedWithPositiveFunding();
        if (pfTotal > 0) {
            patterns.put("펀딩비 양수", String.format("%.0f%% (%d건)", (double) pfWins / pfTotal * 100, pfTotal));
        }
        for (String trend : List.of("UPTREND", "DOWNTREND", "SIDEWAYS")) {
            long tw = tradeRepository.countWinsByTrend(trend);
            long tt = tradeRepository.countResolvedByTrend(trend);
            if (tt > 0) patterns.put(trend, String.format("%.0f%% (%d건)", (double) tw / tt * 100, tt));
        }
        map.put("patterns", patterns);
        return ResponseEntity.ok(map);
    }

    // ===== 배팅 목록 (HOLD 포함) =====
    @GetMapping("/trades")
    @ResponseBody
    public ResponseEntity<List<Trade>> trades(
            @RequestParam(required = false, defaultValue = "") String coin,
            @RequestParam(required = false, defaultValue = "") String timeframe) {
        if (!coin.isEmpty() && !timeframe.isEmpty()) {
            return ResponseEntity.ok(tradeRepository.findTop20ByCoinAndTimeframeIncludingLegacy(coin, timeframe));
        } else if (!coin.isEmpty()) {
            return ResponseEntity.ok(tradeRepository.findTop20ByCoinOrderByCreatedAtDesc(coin));
        }
        return ResponseEntity.ok(tradeRepository.findTop20ByOrderByCreatedAtDesc());
    }

    @GetMapping("/trigger-config")
    @ResponseBody
    public ResponseEntity<?> getTriggerConfig(@RequestParam(defaultValue = "BTC") String coin) {
        return ResponseEntity.ok(triggerConfigService.toMap(coin));
    }

    @GetMapping("/trigger-config/all")
    @ResponseBody
    public ResponseEntity<?> getAllTriggerConfigs() {
        Map<String, Object> result = new HashMap<>();
        result.put("BTC", triggerConfigService.toMap("BTC"));
        result.put("ETH", triggerConfigService.toMap("ETH"));
        result.put("SOL", triggerConfigService.toMap("SOL"));
        result.put("XRP", triggerConfigService.toMap("XRP"));
        result.put("BTC_15M", triggerConfigService.toMap("BTC_15M"));
        result.put("ETH_15M", triggerConfigService.toMap("ETH_15M"));
        result.put("SOL_15M", triggerConfigService.toMap("SOL_15M"));
        result.put("XRP_15M", triggerConfigService.toMap("XRP_15M"));
        return ResponseEntity.ok(result);
    }

    // ===== 개별 트레이드 Claude 분석 조회 =====
    @GetMapping("/trade/{id}/analysis")
    @ResponseBody
    public ResponseEntity<?> getTradeAnalysis(@PathVariable Long id) {
        return tradeRepository.findById(id).map(trade -> {
            Map<String, Object> result = new HashMap<>();
            result.put("id", trade.getId());
            result.put("reason", trade.getReason());
            result.put("claudeAnalysis", trade.getClaudeAnalysis());
            result.put("reflection", trade.getReflection());
            result.put("action", trade.getAction() != null ? trade.getAction().name() : null);
            result.put("confidence", trade.getConfidence());
            result.put("result", trade.getResult() != null ? trade.getResult().name() : null);
            result.put("coin", trade.getCoin());
            result.put("timeframe", trade.getTimeframe());
            result.put("betAmount", trade.getBetAmount());
            result.put("profitLoss", trade.getProfitLoss());
            result.put("openPrice", trade.getOpenPrice());
            result.put("entryPrice", trade.getEntryPrice());
            result.put("exitPrice", trade.getExitPrice());
            result.put("createdAt", trade.getCreatedAt() != null ? trade.getCreatedAt().toString() : null);
            result.put("fundingRate", trade.getFundingRate());
            result.put("buyOdds", trade.getBuyOdds());
            result.put("openInterestChange", trade.getOpenInterestChange());
            result.put("btcChange1h", trade.getBtcChange1h());
            result.put("ethChange1h", trade.getEthChange1h());
            result.put("fearGreedIndex", trade.getFearGreedIndex());
            result.put("marketTrend", trade.getMarketTrend());
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ===== 배팅 기록 전체 삭제 =====
    @DeleteMapping("/trades/all")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteAllTrades() {
        long count = tradeRepository.count();
        tradeRepository.deleteAll();
        balanceService.recalcFromDb(); // 잔액 리셋
        return ResponseEntity.ok(Map.of("status", "deleted", "count", String.valueOf(count)));
    }

    // ===== AI 교훈 조회 (3계층 Level 2) =====
    @GetMapping("/lessons")
    @ResponseBody
    public ResponseEntity<?> getLessons() {
        var lessons = lessonService.getActiveLessons();
        return ResponseEntity.ok(lessons.stream().map(l -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId());
            m.put("lesson", l.getLesson());
            m.put("category", l.getCategory());
            m.put("evidenceCount", l.getEvidenceCount());
            m.put("importance", l.getImportance());
            m.put("updatedAt", l.getUpdatedAt() != null ? l.getUpdatedAt().toString() : null);
            return m;
        }).toList());
    }

    // ===== 교훈 수동 압축 트리거 =====
    @PostMapping("/lessons/compress")
    @ResponseBody
    public ResponseEntity<Map<String, String>> compressLessons() {
        new Thread(() -> lessonService.compressLessons()).start();
        return ResponseEntity.ok(Map.of("status", "compressing"));
    }

    // ===== 폴리마켓 오즈 테스트 =====
    @GetMapping("/odds")
    @ResponseBody
    public ResponseEntity<?> testOdds(
            @RequestParam(defaultValue = "BTC") String coin,
            @RequestParam(defaultValue = "1H") String timeframe) {
        var odds = getOddsForCoinAndTimeframe(coin, timeframe);
        Map<String, Object> result = new HashMap<>();
        result.put("coin", coin);
        result.put("timeframe", timeframe);
        // 소수점 1자리까지 보존 (극단값 0.2¢, 99.8¢ 등 정확히 표시)
        double upCentsRaw = odds.upOdds() * 100;
        double downCentsRaw = odds.downOdds() * 100;
        result.put("upCents", Math.round(upCentsRaw * 10.0) / 10.0);   // 99.6, 0.2 등
        result.put("downCents", Math.round(downCentsRaw * 10.0) / 10.0);
        result.put("upPct", upCentsRaw);
        result.put("downPct", downCentsRaw);
        result.put("spread", Math.round((odds.upOdds() + odds.downOdds() - 1.0) * 100));
        result.put("marketId", odds.marketId());
        result.put("slug", odds.slug());
        result.put("available", odds.available());
        return ResponseEntity.ok(result);
    }

    // ===== 갭 스캐너 실시간 현황 =====
    @GetMapping("/gaps")
    @ResponseBody
    public ResponseEntity<?> gapStatus() {
        var gaps = oddsGapScanner.getLatestGaps();
        Map<String, Object> result = new HashMap<>();
        gaps.forEach((key, snap) -> {
            Map<String, Object> g = new HashMap<>();
            g.put("coin", snap.coin());
            g.put("timeframe", snap.timeframe());
            g.put("direction", snap.direction());
            g.put("priceDiffPct", Math.round(snap.priceDiffPct() * 1000.0) / 1000.0);
            g.put("estimatedProb", Math.round(snap.estimatedProb() * 1000.0) / 1000.0);
            g.put("marketOdds", Math.round(snap.marketOdds() * 1000.0) / 1000.0);
            g.put("gap", Math.round(snap.gap() * 1000.0) / 1000.0);
            g.put("gapPct", Math.round(snap.gap() * 10000.0) / 100.0);
            g.put("streakSeconds", snap.streakSeconds());
            g.put("age", (System.currentTimeMillis() - snap.timestamp()) / 1000);
            // 역방향 정보
            g.put("reverseDirection", snap.reverseDirection());
            g.put("reverseEstProb", Math.round(snap.reverseEstProb() * 1000.0) / 1000.0);
            g.put("reverseMarketOdds", Math.round(snap.reverseMarketOdds() * 1000.0) / 1000.0);
            g.put("reverseGap", Math.round(snap.reverseGap() * 1000.0) / 1000.0);
            g.put("reverseGapPct", Math.round(snap.reverseGap() * 10000.0) / 100.0);
            g.put("reverseStreakSeconds", snap.reverseStreakSeconds());
            result.put(key, g);
        });
        return ResponseEntity.ok(result);
    }

    // ===== ⭐ V5: 갭스캐너 실시간 활동 로그 =====
    @GetMapping("/scan-logs")
    @ResponseBody
    public ResponseEntity<?> scanLogs() {
        var logs = oddsGapScanner.getRecentScanLogs();
        return ResponseEntity.ok(logs.stream().map(l -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("time", l.timestamp());
            m.put("coin", l.coin());
            m.put("tf", l.timeframe());
            m.put("stage", l.stage());
            m.put("detail", l.detail());
            return m;
        }).toList());
    }

    // ===== 폴리마켓 오즈 벌크 조회 (모든 코인 + 타임프레임 한 번에, 병렬) =====
    @GetMapping("/odds/all")
    @ResponseBody
    public ResponseEntity<?> allOdds() {
        String[] coins = {"BTC", "ETH", "SOL", "XRP"};
        String[] timeframes = {"1H", "15M", "5M"};

        // 병렬로 모든 오즈 조회
        Map<String, java.util.concurrent.CompletableFuture<Map<String, Object>>> futures = new HashMap<>();
        for (String tf : timeframes) {
            for (String coin : coins) {
                String key = tf + "_" + coin;
                final String c = coin;
                final String t = tf;
                futures.put(key, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    var odds = getOddsForCoinAndTimeframe(c, t);
                    Map<String, Object> coinData = new HashMap<>();
                    double upCentsRaw = odds.upOdds() * 100;
                    double downCentsRaw = odds.downOdds() * 100;
                    coinData.put("upCents", Math.round(upCentsRaw * 10.0) / 10.0);
                    coinData.put("downCents", Math.round(downCentsRaw * 10.0) / 10.0);
                    coinData.put("upPct", upCentsRaw);
                    coinData.put("downPct", downCentsRaw);
                    coinData.put("spread", Math.round((odds.upOdds() + odds.downOdds() - 1.0) * 100));
                    coinData.put("slug", odds.slug());
                    coinData.put("available", odds.available());
                    return coinData;
                }));
            }
        }

        // 결과 조립
        Map<String, Object> result = new HashMap<>();
        for (String tf : timeframes) {
            Map<String, Object> tfMap = new HashMap<>();
            for (String coin : coins) {
                try {
                    tfMap.put(coin, futures.get(tf + "_" + coin).get(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (Exception e) {
                    Map<String, Object> fallback = new HashMap<>();
                    fallback.put("upCents", 50.0);
                    fallback.put("downCents", 50.0);
                    fallback.put("upPct", 50.0);
                    fallback.put("downPct", 50.0);
                    fallback.put("spread", 0);
                    fallback.put("slug", "");
                    fallback.put("available", false);
                    tfMap.put(coin, fallback);
                }
            }
            result.put(tf, tfMap);
        }
        return ResponseEntity.ok(result);
    }

    private PolymarketOddsService.MarketOdds getOddsForCoinAndTimeframe(String coin, String timeframe) {
        if ("5M".equalsIgnoreCase(timeframe)) {
            return oddsService.getOdds5mForCoin(coin.toUpperCase());
        }
        if ("15M".equalsIgnoreCase(timeframe)) {
            return oddsService.getOdds15mForCoin(coin.toUpperCase());
        }
        return oddsService.getOddsForCoin(coin.toUpperCase());
    }
}
