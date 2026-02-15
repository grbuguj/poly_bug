package com.example.poly_bug.service;

import com.example.poly_bug.config.CoinConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * 바이낸스 WebSocket 멀티코인 실시간 가격 스트림
 *
 * - CoinConfig의 모든 코인 자동 구독
 * - 롤링 윈도우로 최근 가격 유지
 * - 급변동 콜백 즉시 발동
 */
@Slf4j
@Service
public class BinanceWebSocketService {

    private static final int PRICE_WINDOW_SIZE = 120;

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 코인별 최근 가격 (thread-safe)
    private final Map<String, Deque<PriceTick>> priceHistory = new ConcurrentHashMap<>();

    // 코인별 최신 가격 (빠른 접근)
    private final Map<String, Double> latestPrices = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<>();

    // 급변동 콜백
    private BiConsumer<String, PriceSpike> spikeCallback;

    private WebSocket webSocket;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;

    public record PriceTick(double price, long timestamp) {}
    public record PriceSpike(String coin, double fromPrice, double toPrice, double changePct, long durationMs) {}

    @PostConstruct
    public void init() {
        // 모든 코인 히스토리 초기화
        for (CoinConfig.CoinDef coin : CoinConfig.ACTIVE_COINS) {
            priceHistory.put(coin.label(), new ConcurrentLinkedDeque<>());
            latestPrices.put(coin.label(), 0.0);
            lastUpdateTimes.put(coin.label(), 0L);
        }
        connect();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (webSocket != null) webSocket.close(1000, "shutdown");
        reconnectExecutor.shutdownNow();
    }

    public void onSpike(BiConsumer<String, PriceSpike> callback) {
        this.spikeCallback = callback;
    }

    // === 하위 호환 (기존 코드용) ===
    public double getBtcPrice() { return getPrice("BTC"); }
    public double getEthPrice() { return getPrice("ETH"); }
    public long getBtcLastUpdate() { return lastUpdateTimes.getOrDefault("BTC", 0L); }
    public long getEthLastUpdate() { return lastUpdateTimes.getOrDefault("ETH", 0L); }

    /** 코인 라벨로 최신 가격 조회 */
    public double getPrice(String coinLabel) {
        return latestPrices.getOrDefault(coinLabel, 0.0);
    }

    /**
     * 최근 N초간 가격 변동률 계산
     */
    public double getPriceChangePct(String coin, int lookbackSeconds) {
        Deque<PriceTick> history = priceHistory.get(coin);
        if (history == null || history.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        long cutoff = now - (lookbackSeconds * 1000L);

        PriceTick latest = history.peekLast();
        PriceTick oldest = null;

        for (PriceTick tick : history) {
            if (tick.timestamp >= cutoff) {
                oldest = tick;
                break;
            }
        }

        if (oldest == null || latest == null || oldest.price == 0) return 0;
        return ((latest.price - oldest.price) / oldest.price) * 100;
    }

    private void connect() {
        running = true;
        String wsUrl = CoinConfig.buildWsUrl();
        log.info("🔌 바이낸스 WebSocket 연결: {}", wsUrl);

        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("🔌 바이낸스 WebSocket 연결 성공 | 코인: {}개",
                        CoinConfig.ACTIVE_COINS.size());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                processTick(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.warn("⚠️ WebSocket 연결 끊김: {}", t.getMessage());
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("WebSocket 종료: {} {}", code, reason);
                if (running) scheduleReconnect();
            }
        });
    }

    private void processTick(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String symbol = node.path("s").asText();
            double price = node.path("p").asDouble();
            long timestamp = node.path("T").asLong();

            // 심볼 → 라벨 변환 (BTCUSDT → BTC)
            String coin = CoinConfig.symbolToLabel(symbol);
            if (coin.equals(symbol)) return; // 미등록 코인

            // 가격 갱신
            latestPrices.put(coin, price);
            lastUpdateTimes.put(coin, timestamp);

            // 히스토리 추가
            Deque<PriceTick> history = priceHistory.get(coin);
            if (history != null) {
                history.addLast(new PriceTick(price, timestamp));
                while (history.size() > PRICE_WINDOW_SIZE) {
                    history.pollFirst();
                }
            }

            // 급변동 체크 (10초 윈도우)
            checkSpike(coin, price, timestamp);

        } catch (Exception e) {
            // 파싱 실패 무시
        }
    }

    /**
     * 10초 내 0.25%+ 변동 감지
     */
    private void checkSpike(String coin, double currentPrice, long now) {
        if (spikeCallback == null) return;

        Deque<PriceTick> history = priceHistory.get(coin);
        if (history == null || history.size() < 5) return;

        long cutoff = now - 10_000;
        PriceTick refTick = null;
        for (PriceTick tick : history) {
            if (tick.timestamp >= cutoff) {
                refTick = tick;
                break;
            }
        }

        if (refTick == null || refTick.price == 0) return;

        double changePct = ((currentPrice - refTick.price) / refTick.price) * 100;

        if (Math.abs(changePct) >= 0.25) {
            PriceSpike spike = new PriceSpike(
                    coin, refTick.price, currentPrice, changePct,
                    now - refTick.timestamp
            );
            spikeCallback.accept(coin, spike);
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        reconnectExecutor.schedule(() -> {
            log.info("🔄 WebSocket 재연결 시도...");
            connect();
        }, 3, TimeUnit.SECONDS);
    }
}
