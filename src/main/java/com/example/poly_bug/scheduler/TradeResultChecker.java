package com.example.poly_bug.scheduler;

import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.service.TradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 배팅 결과 자동 체크 스케줄러
 * 1분마다 실행, PENDING 상태인 배팅 중 해당 시간 1H 캔들이 닫힌 것들 체크
 * 정시(:00) 종가 기준으로 WIN/LOSE 판정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeResultChecker {

    private final TradeRepository tradeRepository;
    private final TradingService tradingService;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void checkPendingTrades() {
        List<Trade> pending = tradeRepository.findByResult(Trade.TradeResult.PENDING);
        if (pending.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        // 현재 시각의 정시 (예: 9:15 → 9:00)
        LocalDateTime currentHour = now.truncatedTo(ChronoUnit.HOURS);

        // 판정 가능 = 캔들 마감 시각이 지난 것
        long readyCount = pending.stream()
                .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                .filter(t -> {
                    String tf = t.getTimeframe() != null ? t.getTimeframe() : "1H";
                    LocalDateTime candleClose;
                    if ("5M".equals(tf)) {
                        int minute = t.getCreatedAt().getMinute();
                        int windowStart = (minute / 5) * 5;
                        candleClose = t.getCreatedAt().truncatedTo(ChronoUnit.HOURS)
                                .plusMinutes(windowStart + 5);
                    } else if ("15M".equals(tf)) {
                        int minute = t.getCreatedAt().getMinute();
                        int windowStart = (minute / 15) * 15;
                        candleClose = t.getCreatedAt().truncatedTo(ChronoUnit.HOURS)
                                .plusMinutes(windowStart + 15);
                    } else {
                        candleClose = t.getCreatedAt().truncatedTo(ChronoUnit.HOURS).plusHours(1);
                    }
                    return now.isAfter(candleClose);
                })
                .count();

        tradingService.broadcast(String.format("🔍 PENDING %d건 체크 (대기: %d건, 판정 가능: %d건)",
                pending.size(), pending.size() - readyCount, readyCount));

        int checked = 0;

        for (Trade trade : pending) {
            if (trade.getAction() == Trade.TradeAction.HOLD) continue;

            // 타임프레임에 따라 캔들 마감 시각 계산
            String tf = trade.getTimeframe() != null ? trade.getTimeframe() : "1H";
            LocalDateTime candleClose;
            if ("5M".equals(tf)) {
                // 5분 캔들: 배팅 시간을 5분 단위로 내림 + 5분
                int minute = trade.getCreatedAt().getMinute();
                int windowStart = (minute / 5) * 5;
                candleClose = trade.getCreatedAt().truncatedTo(ChronoUnit.HOURS)
                        .plusMinutes(windowStart + 5);
            } else if ("15M".equals(tf)) {
                // 15분 캔들: 배팅 시간을 15분 단위로 내림 + 15분
                int minute = trade.getCreatedAt().getMinute();
                int windowStart = (minute / 15) * 15;
                candleClose = trade.getCreatedAt().truncatedTo(ChronoUnit.HOURS)
                        .plusMinutes(windowStart + 15);
            } else {
                // 1H 캔들: 다음 정시
                candleClose = trade.getCreatedAt().truncatedTo(ChronoUnit.HOURS).plusHours(1);
            }

            // 캔들 마감 전이면 아직 대기
            if (now.isBefore(candleClose)) {
                long remainMin = ChronoUnit.MINUTES.between(now, candleClose);
                log.debug("Trade #{} [{}{}] 대기 중 (마감까지 {}분)",
                        trade.getId(), trade.getCoin(), tf, Math.max(0, remainMin));
                continue;
            }

            try {
                double[] openAndClose = getCandleOpenAndClose(trade.getCoin(), trade.getCreatedAt(), tf);
                double candleOpen = openAndClose[0];
                double closePrice = openAndClose[1];
                if (closePrice <= 0) {
                    tradingService.broadcast(String.format("⚠️ Trade #%d [%s] 종가 조회 실패 — 재시도 예정",
                            trade.getId(), trade.getCoin()));
                    continue;
                }

                // openPrice가 DB에 없으면 캔들 시가로 채워넣기
                if (trade.getOpenPrice() == null && candleOpen > 0) {
                    trade.setOpenPrice(candleOpen);
                }

                // 판정: 시초가 vs 종가
                double refOpen = trade.getOpenPrice() != null ? trade.getOpenPrice() : candleOpen;
                Trade.TradeResult result = determineResult(trade, refOpen, closePrice);
                String symbol = switch (trade.getCoin()) {
                    case "BTC" -> "₿";
                    case "ETH" -> "Ξ";
                    case "SOL" -> "☀";
                    case "XRP" -> "◆";
                    default -> "💰";
                };
                String emoji = result == Trade.TradeResult.WIN ? "✅" : "❌";
                String actionStr = trade.getAction() == Trade.TradeAction.BUY_YES ? "UP" : "DOWN";

                tradingService.broadcast(String.format(
                        "%s [%s %s] #%d %s | 시초가 $%s → 종가 $%s → %s",
                        emoji, symbol, trade.getCoin(), trade.getId(), actionStr,
                        String.format("%.2f", refOpen),
                        String.format("%.2f", closePrice),
                        result));

                tradingService.updateTradeResult(trade.getId(), result, closePrice);
                checked++;

            } catch (Exception e) {
                log.error("Trade #{} 체크 실패: {}", trade.getId(), e.getMessage());
                tradingService.broadcast(String.format("❌ Trade #%d 체크 오류: %s",
                        trade.getId(), e.getMessage()));
            }
        }

        if (checked > 0) {
            tradingService.broadcast(String.format("📊 자동 판정 완료: %d건", checked));
        }
    }

    /**
     * 배팅 시간이 속한 캔들의 종가 조회 + 시가(시초가)도 함께 반환
     * 1H: 14:38 배팅 → 14:00~15:00 캔들의 open, close
     * 15M: 14:38 배팅 → 14:30~14:45 캔들의 open, close
     * 5M: 14:38 배팅 → 14:35~14:40 캔들의 open, close
     */
    private double[] getCandleOpenAndClose(String coin, LocalDateTime tradeTime, String timeframe) throws Exception {
        String symbol = coin + "USDT";
        String interval;
        LocalDateTime candleStart;

        if ("5M".equals(timeframe)) {
            interval = "5m";
            int minute = tradeTime.getMinute();
            int windowStart = (minute / 5) * 5;
            candleStart = tradeTime.truncatedTo(ChronoUnit.HOURS).plusMinutes(windowStart);
        } else if ("15M".equals(timeframe)) {
            interval = "15m";
            int minute = tradeTime.getMinute();
            int windowStart = (minute / 15) * 15;
            candleStart = tradeTime.truncatedTo(ChronoUnit.HOURS).plusMinutes(windowStart);
        } else {
            interval = "1h";
            candleStart = tradeTime.truncatedTo(ChronoUnit.HOURS);
        }

        long startMs = candleStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        String url = String.format(
                "https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&startTime=%d&limit=1",
                symbol, interval, startMs
        );

        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) return new double[]{0, 0};
            JsonNode data = objectMapper.readTree(res.body().string());
            if (!data.isArray() || data.isEmpty()) return new double[]{0, 0};

            // [0]=openTime, [1]=open, [2]=high, [3]=low, [4]=close
            double open = data.get(0).get(1).asDouble();
            double close = data.get(0).get(4).asDouble();
            return new double[]{open, close};
        }
    }

    /**
     * 폴리마켓 판정: 종가 vs 시초가(캔들 시가) 비교
     * 시초가보다 종가가 높으면 UP WIN, 낮으면 DOWN WIN
     */
    private Trade.TradeResult determineResult(Trade trade, double openPrice, double closePrice) {
        boolean priceUp = closePrice > openPrice;
        boolean betUp = trade.getAction() == Trade.TradeAction.BUY_YES;
        return (priceUp == betUp) ? Trade.TradeResult.WIN : Trade.TradeResult.LOSE;
    }
}
