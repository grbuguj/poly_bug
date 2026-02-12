package com.example.poly_bug.controller;

import com.example.poly_bug.dto.MarketIndicators;
import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.service.BotStateService;
import com.example.poly_bug.service.MarketDataService;
import com.example.poly_bug.service.TradingService;
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
    private final BotStateService botStateService;
    private final TradingService tradingService;
    private final MarketDataService marketDataService;

    @GetMapping("/")
    public String dashboard(Model model) {
        return "dashboard";
    }

    // ===== 봇 제어 =====
    @PostMapping("/bot/start")
    @ResponseBody
    public ResponseEntity<Map<String, String>> start() {
        botStateService.start();
        tradingService.broadcast("🟢 봇 시작됨");
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @PostMapping("/bot/stop")
    @ResponseBody
    public ResponseEntity<Map<String, String>> stop() {
        botStateService.stop();
        tradingService.broadcast("🔴 봇 정지됨");
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/bot/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("running", botStateService.isRunning());
        map.put("cycleCount", botStateService.getCycleCount());
        map.put("lastAction", botStateService.getLastAction());
        map.put("lastRunTime", botStateService.getLastRunAt());

        Long resolved = tradeRepository.countResolved();
        Long wins = tradeRepository.countWins();
        Double pnl = tradeRepository.totalProfitLoss();
        map.put("totalTrades", tradeRepository.count());
        map.put("winRate", resolved > 0 ? String.format("%.1f%%", (double) wins / resolved * 100) : "0%");
        map.put("pnl", pnl != null ? String.format("%+.2f", pnl) : "0.00");
        return ResponseEntity.ok(map);
    }

    // ===== 즉시 1회 실행 =====
    @PostMapping("/trade/run")
    @ResponseBody
    public ResponseEntity<Map<String, String>> runOnce() {
        new Thread(() -> {
            tradingService.executeCycle("BTC");
            tradingService.executeCycle("ETH");
        }).start();
        return ResponseEntity.ok(Map.of("status", "started"));
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

    // ===== 통계 (coin 파라미터 지원) =====
    @GetMapping("/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam(required = false, defaultValue = "") String coin) {

        boolean filtered = !coin.isEmpty();

        Long total = filtered ? tradeRepository.count() : tradeRepository.count(); // 전체
        Long resolved, wins;
        Double pnl;

        if (filtered) {
            resolved = tradeRepository.countResolvedByCoin(coin);
            wins = tradeRepository.countWinsByCoin(coin);
            pnl = tradeRepository.totalProfitLossByCoin(coin);
            total = (long) tradeRepository.findTop50ByCoinOrderByCreatedAtDesc(coin).size();
        } else {
            resolved = tradeRepository.countResolved();
            wins = tradeRepository.countWins();
            pnl = tradeRepository.totalProfitLoss();
            total = tradeRepository.count();
        }

        Map<String, Object> map = new HashMap<>();
        map.put("total", total);
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

    // ===== 배팅 목록 (coin 파라미터 지원) =====
    @GetMapping("/trades")
    @ResponseBody
    public ResponseEntity<List<Trade>> trades(
            @RequestParam(required = false, defaultValue = "") String coin) {
        if (!coin.isEmpty()) {
            return ResponseEntity.ok(tradeRepository.findTop20ByCoinOrderByCreatedAtDesc(coin));
        }
        return ResponseEntity.ok(tradeRepository.findTop20ByOrderByCreatedAtDesc());
    }
}
