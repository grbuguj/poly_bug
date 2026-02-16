package com.example.poly_bug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 폴리마켓 RTDS WebSocket → Chainlink 가격 수신
 * 15M/5M 마켓의 시초가/종가 판정에 사용 (폴리마켓이 Chainlink 오라클로 판정)
 *
 * ⭐ V6: 링 버퍼 기반 정밀 시초가 매칭
 * - 모든 Chainlink 메시지를 (price, timestamp)로 버퍼링
 * - 캔들 경계 전환 시, 경계 타임스탬프에 가장 가까운 가격을 시초가로 사용
 * - 이전 방식(previousTickPrice)은 서버 시간 기반이라 $30+ 오차 발생
 *
 * 엔드포인트: wss://ws-live-data.polymarket.com
 * 토픽: crypto_prices_chainlink (btc/usd, eth/usd, sol/usd, xrp/usd)
 */
@Slf4j
@Service
public class ChainlinkPriceService {

    private static final String RTDS_WS_URL = "wss://ws-live-data.polymarket.com";
    private static final long PING_INTERVAL_MS = 5_000; // 5초마다 ping (공식 권장)

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket은 무기한
            .pingInterval(5, TimeUnit.SECONDS)
            .build();

    // Chainlink 실시간 가격: "BTC" → price
    private final Map<String, Double> latestChainlinkPrices = new ConcurrentHashMap<>();
    // 가격 갱신 시각: "BTC" → timestamp(ms)
    private final Map<String, Long> priceTimestamps = new ConcurrentHashMap<>();

    // ⭐ V6: 코인별 가격 링 버퍼 (최근 1000개 = 약 16분분량)
    // 15M boundary 매칭 위해 최소 15분 이상 보관 필요
    // 각 항목: [chainlink_timestamp_seconds, price]
    private final Map<String, Deque<double[]>> priceRingBuffer = new ConcurrentHashMap<>();
    private static final int RING_BUFFER_SIZE = 1000;

    // 15M/5M 캔들 시초가 캐시 (캔들 시작 시 Chainlink 가격 스냅샷)
    private final Map<String, Double> chainlink15mOpen = new ConcurrentHashMap<>();
    private final Map<String, Double> chainlink5mOpen = new ConcurrentHashMap<>();
    // 코인별 독립 윈도우 추적 (경계 타임스탬프 기반)
    private final Map<String, Long> coinLast15mBoundary = new ConcurrentHashMap<>();
    private final Map<String, Long> coinLast5mBoundary = new ConcurrentHashMap<>();

    // ⭐ V7: 5M/15M 캔들 종가 스냅샷 (캔들 전환 시 이전 캔들의 마지막 가격)
    // Key: "COIN:boundaryTsSec" (예: "BTC:1700000100"), Value: close price
    // TradeResultChecker에서 5M/15M 결과 판정 시 사용
    private final Map<String, Double> chainlink5mClose = new ConcurrentHashMap<>();
    private final Map<String, Double> chainlink15mClose = new ConcurrentHashMap<>();
    private static final int MAX_CLOSE_CACHE_SIZE = 100; // 코인당 최대 100개 캐시

    private WebSocket webSocket;
    private volatile boolean connected = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 심볼 매핑: Chainlink → 내부 라벨
    private static final Map<String, String> SYMBOL_MAP = Map.of(
            "btc/usd", "BTC",
            "eth/usd", "ETH",
            "sol/usd", "SOL",
            "xrp/usd", "XRP"
    );

    @PostConstruct
    public void init() {
        connect();
        // 재연결 스케줄러 (30초마다 체크)
        scheduler.scheduleAtFixedRate(() -> {
            if (!connected) {
                log.warn("⚡ Chainlink RTDS 연결 끊김 → 재연결 시도");
                connect();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        if (webSocket != null) webSocket.close(1000, "shutdown");
    }

    private void connect() {
        Request request = new Request.Builder().url(RTDS_WS_URL).build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                log.info("✅ Chainlink RTDS WebSocket 연결됨: {}", RTDS_WS_URL);

                // Chainlink 가격 구독
                String subscribeMsg = """
                    {
                      "action": "subscribe",
                      "subscriptions": [
                        {
                          "topic": "crypto_prices_chainlink",
                          "type": "*",
                          "filters": ""
                        },
                        {
                          "topic": "crypto_prices",
                          "type": "*",
                          "filters": ""
                        }
                      ]
                    }
                    """;
                ws.send(subscribeMsg);
                log.info("📡 Chainlink + Binance 가격 구독 요청 전송");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    // ⭐ 디버그: raw 로깅은 제거 (초당 수십건 → 너무 많음)
                    JsonNode msg = objectMapper.readTree(text);
                    String topic = msg.path("topic").asText("");

                    if ("crypto_prices_chainlink".equals(topic)) {
                        JsonNode payload = msg.path("payload");
                        String symbol = payload.path("symbol").asText(""); // "btc/usd"
                        double value = payload.path("value").asDouble(0);
                        long timestamp = payload.path("timestamp").asLong(0);

                        String label = SYMBOL_MAP.get(symbol.toLowerCase());
                        if (label != null && value > 0) {
                            // ⭐ V6: 링 버퍼에 (timestamp, price) 저장
                            long tsSec = timestamp > 1_000_000_000_000L ? timestamp / 1000 : timestamp;
                            Deque<double[]> buffer = priceRingBuffer.computeIfAbsent(
                                    label, k -> new ConcurrentLinkedDeque<>());
                            buffer.addLast(new double[]{tsSec, value});
                            while (buffer.size() > RING_BUFFER_SIZE) buffer.pollFirst();

                            latestChainlinkPrices.put(label, value);
                            priceTimestamps.put(label, System.currentTimeMillis());

                            // 15M/5M 시초가 스냅샷 체크
                            updateOpenPriceSnapshots(label, value, tsSec);
                        } else {
                            log.warn("⛓ Chainlink 파싱 실패: symbol={}, value={}, label={}", symbol, value, label);
                        }
                    } else if ("crypto_prices".equals(topic)) {
                        // Binance 가격 (1H 판정용) — 참고 로깅만
                        JsonNode payload = msg.path("payload");
                        String symbol = payload.path("symbol").asText("");
                        double value = payload.path("value").asDouble(0);
                        log.debug("📊 RTDS Binance [{}] = {}", symbol, value);
                    } else if (!topic.isEmpty()) {
                        // ⭐ 미지 토픽 탐색 — price_to_beat 등 있는지 확인
                        log.info("📨 RTDS 미지 토픽: {} | raw: {}", topic, 
                                text.length() > 300 ? text.substring(0, 300) : text);
                    }
                } catch (Exception e) {
                    log.warn("Chainlink 메시지 파싱 오류: {} | raw: {}", e.getMessage(), 
                            text.length() > 200 ? text.substring(0, 200) : text);
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected = false;
                log.warn("⚠️ Chainlink RTDS 연결 종료: {} {}", code, reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                String responseInfo = response != null ? 
                        String.format("code=%d, msg=%s", response.code(), response.message()) : "null";
                log.error("❌ Chainlink RTDS 연결 실패: {} | response: {}", t.getMessage(), responseInfo);
            }
        });
    }

    /**
     * ⭐ V6: 링 버퍼 기반 정밀 시초가 매칭
     * 
     * 1. 현재 시각에서 5M/15M 경계 타임스탬프 계산 (Unix epoch, 300/900 배수)
     * 2. 경계가 바뀌면 링 버퍼에서 경계 타임스탬프 이하의 가장 최근 가격을 찾음
     * 3. 이 가격 = 폴리마켓의 PRICE TO BEAT와 동일 (동일 Chainlink 소스)
     */
    private void updateOpenPriceSnapshots(String coin, double currentPrice, long msgTsSec) {
        // 현재 시각의 5M/15M 경계 타임스탬프 (UTC 기준, Unix 300/900 배수)
        long nowSec = System.currentTimeMillis() / 1000;
        long boundary5m = nowSec - (nowSec % 300);   // 5분 = 300초
        long boundary15m = nowSec - (nowSec % 900);  // 15분 = 900초

        // 5M 윈도우 전환 체크
        Long last5m = coinLast5mBoundary.get(coin);
        if (last5m == null || boundary5m != last5m) {
            // ⭐ V7: 이전 5M 캔들의 종가 스냅샷 저장 (전환 직전의 현재가 = 이전 캔들 종가)
            if (last5m != null && last5m != boundary5m) {
                String closeKey = coin + ":" + last5m;
                chainlink5mClose.put(closeKey, currentPrice);
                cleanupCloseCache(chainlink5mClose, coin);
                log.info("📸 [V7] Chainlink 5M close [{}] boundary={} → close={}", coin, last5m, currentPrice);
            }

            coinLast5mBoundary.put(coin, boundary5m);
            double openPrice = findPriceAtBoundary(coin, boundary5m, currentPrice);
            if (openPrice > 0) {
                chainlink5mOpen.put(coin, openPrice);
                log.info("⏰ [V6] Chainlink 5M open [{}] = {} (boundary={}, msgTs={}, now={}, bufSize={})",
                        coin, openPrice, boundary5m, msgTsSec, nowSec,
                        priceRingBuffer.getOrDefault(coin, new ConcurrentLinkedDeque<>()).size());
            } else {
                log.warn("⚠️ [V6] {} 5M open 데이터 부족 → 0 유지 (boundary={})", coin, boundary5m);
            }
        } else if (chainlink5mOpen.getOrDefault(coin, 0.0) == 0) {
            // ⭐ 이전에 0으로 세팅됨 → 버퍼에 데이터 쌓였으면 재시도
            double openPrice = findPriceAtBoundary(coin, boundary5m, currentPrice);
            if (openPrice > 0) {
                chainlink5mOpen.put(coin, openPrice);
                log.info("🔄 [V6] {} 5M open 재시도 성공: {} (boundary={})", coin, openPrice, boundary5m);
            }
        }

        // 15M 윈도우 전환 체크
        Long last15m = coinLast15mBoundary.get(coin);
        if (last15m == null || boundary15m != last15m) {
            // ⭐ V7: 이전 15M 캔들의 종가 스냅샷 저장
            if (last15m != null && last15m != boundary15m) {
                String closeKey = coin + ":" + last15m;
                chainlink15mClose.put(closeKey, currentPrice);
                cleanupCloseCache(chainlink15mClose, coin);
                log.info("📸 [V7] Chainlink 15M close [{}] boundary={} → close={}", coin, last15m, currentPrice);
            }

            coinLast15mBoundary.put(coin, boundary15m);
            double openPrice = findPriceAtBoundary(coin, boundary15m, currentPrice);
            if (openPrice > 0) {
                chainlink15mOpen.put(coin, openPrice);
                log.info("⏰ [V6] Chainlink 15M open [{}] = {} (boundary={})", coin, openPrice, boundary15m);
            } else {
                log.warn("⚠️ [V6] {} 15M open 데이터 부족 → 0 유지 (boundary={})", coin, boundary15m);
            }
        } else if (chainlink15mOpen.getOrDefault(coin, 0.0) == 0) {
            // ⭐ 이전에 0으로 세팅됨 → 버퍼에 데이터 쌓였으면 재시도
            double openPrice = findPriceAtBoundary(coin, boundary15m, currentPrice);
            if (openPrice > 0) {
                chainlink15mOpen.put(coin, openPrice);
                log.info("🔄 [V6] {} 15M open 재시도 성공: {} (boundary={})", coin, openPrice, boundary15m);
            }
        }
    }

    /**
     * ⭐ 링 버퍼에서 boundary 타임스탬프 이하의 가장 최근 가격을 찾음
     * 폴리마켓은 캔들 시작 시점의 "가장 최근 Chainlink 가격"을 PRICE TO BEAT로 사용
     */
    private double findPriceAtBoundary(String coin, long boundaryTsSec, double fallback) {
        Deque<double[]> buffer = priceRingBuffer.get(coin);
        if (buffer == null || buffer.isEmpty()) {
            log.warn("⚠️ [V6] {} 링 버퍼 비어있음 → 0 반환 (Binance fallback 유도)", coin);
            return 0; // 0 반환 → OddsGapScanner/대시보드가 Binance fallback 사용
        }

        // 버퍼를 역순으로 탐색: boundary 이하의 가장 최근 가격
        double bestPrice = 0;
        long bestTs = 0;
        for (Iterator<double[]> it = ((ConcurrentLinkedDeque<double[]>) buffer).descendingIterator(); it.hasNext(); ) {
            double[] entry = it.next();
            long ts = (long) entry[0];
            double price = entry[1];
            if (ts <= boundaryTsSec) {
                bestPrice = price;
                bestTs = ts;
                break; // 역순이니까 첫 히트가 가장 가까운 것
            }
        }

        if (bestTs > 0) {
            long diff = boundaryTsSec - bestTs;
            log.info("🎯 [V6] {} boundary={} → matched ts={} ({}초 전), price={}", 
                    coin, boundaryTsSec, bestTs, diff, bestPrice);
        } else {
            // 버퍼의 모든 항목이 boundary 이후 → 아직 데이터 부족
            log.warn("⚠️ [V6] {} 버퍼에 boundary={} 이하 데이터 없음 (buf oldest={}) → 0 반환",
                    coin, boundaryTsSec, 
                    buffer.peekFirst() != null ? (long) buffer.peekFirst()[0] : -1);
            return 0; // 0 반환 → fallback 유도
        }
        return bestPrice;
    }

    // ===== Public API =====

    /** Chainlink 실시간 가격 조회 */
    public double getPrice(String coin) {
        return latestChainlinkPrices.getOrDefault(coin, 0.0);
    }

    /** Chainlink 15M 캔들 시초가 */
    public double get15mOpen(String coin) {
        return chainlink15mOpen.getOrDefault(coin, 0.0);
    }

    /** Chainlink 5M 캔들 시초가 */
    public double get5mOpen(String coin) {
        return chainlink5mOpen.getOrDefault(coin, 0.0);
    }

    /** 가격 마지막 갱신 시각 (ms) */
    public long getLastUpdateTime(String coin) {
        return priceTimestamps.getOrDefault(coin, 0L);
    }

    /** 연결 상태 */
    public boolean isConnected() {
        return connected;
    }

    /** 전체 Chainlink 가격 맵 (디버깅용) */
    public Map<String, Double> getAllPrices() {
        return Map.copyOf(latestChainlinkPrices);
    }

    /** 전체 15M 시초가 맵 */
    public Map<String, Double> getAll15mOpens() {
        return Map.copyOf(chainlink15mOpen);
    }

    /** 전체 5M 시초가 맵 */
    public Map<String, Double> getAll5mOpens() {
        return Map.copyOf(chainlink5mOpen);
    }

    /** 링 버퍼 상태 (디버그용) */
    public Map<String, Object> getRingBufferStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<double[]>> entry : priceRingBuffer.entrySet()) {
            Deque<double[]> buf = entry.getValue();
            Map<String, Object> coinStatus = new LinkedHashMap<>();
            coinStatus.put("size", buf.size());
            if (!buf.isEmpty()) {
                double[] oldest = buf.peekFirst();
                double[] newest = buf.peekLast();
                coinStatus.put("oldest_ts", (long) oldest[0]);
                coinStatus.put("oldest_price", oldest[1]);
                coinStatus.put("newest_ts", (long) newest[0]);
                coinStatus.put("newest_price", newest[1]);
                coinStatus.put("span_sec", (long) newest[0] - (long) oldest[0]);
            }
            status.put(entry.getKey(), coinStatus);
        }
        return status;
    }

    /** 5M 경계 타임스탬프 (디버그용) */
    public Map<String, Long> get5mBoundaries() {
        return Map.copyOf(coinLast5mBoundary);
    }

    // ===== V7: 종가 스냅샷 API =====

    /**
     * ⭐ V7: 특정 5M 캔들의 Chainlink 종가 조회
     * @param coin "BTC", "ETH" 등
     * @param boundaryTsSec 캔들 시작 시각의 Unix timestamp (초, 300의 배수)
     * @return 종가. 없으면 0.0 (Binance fallback 필요)
     */
    public double get5mClose(String coin, long boundaryTsSec) {
        String key = coin + ":" + boundaryTsSec;
        return chainlink5mClose.getOrDefault(key, 0.0);
    }

    /**
     * ⭐ V7: 특정 15M 캔들의 Chainlink 종가 조회
     * @param coin "BTC", "ETH" 등
     * @param boundaryTsSec 캔들 시작 시각의 Unix timestamp (초, 900의 배수)
     * @return 종가. 없으면 0.0 (Binance fallback 필요)
     */
    public double get15mClose(String coin, long boundaryTsSec) {
        String key = coin + ":" + boundaryTsSec;
        return chainlink15mClose.getOrDefault(key, 0.0);
    }

    /**
     * ⭐ V7: 특정 5M 캔들의 Chainlink 시초가 조회 (링 버퍼 기반)
     * @param coin "BTC" 등
     * @param boundaryTsSec 캔들 시작 시각 (300의 배수)
     * @return 시초가. 없으면 0.0
     */
    public double get5mOpenAt(String coin, long boundaryTsSec) {
        return findPriceAtBoundary(coin, boundaryTsSec, 0);
    }

    /**
     * ⭐ V7: 특정 15M 캔들의 Chainlink 시초가 조회 (링 버퍼 기반)
     * @param coin "BTC" 등
     * @param boundaryTsSec 캔들 시작 시각 (900의 배수)
     * @return 시초가. 없으면 0.0
     */
    public double get15mOpenAt(String coin, long boundaryTsSec) {
        return findPriceAtBoundary(coin, boundaryTsSec, 0);
    }

    /** 종가 캐시 정리: 코인별 MAX_CLOSE_CACHE_SIZE 초과 시 오래된 것 제거 */
    private void cleanupCloseCache(Map<String, Double> cache, String coin) {
        String prefix = coin + ":";
        long count = cache.keySet().stream().filter(k -> k.startsWith(prefix)).count();
        if (count > MAX_CLOSE_CACHE_SIZE) {
            cache.keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .sorted() // 타임스탬프 기반이라 문자열 정렬 = 시간순
                    .limit(count - MAX_CLOSE_CACHE_SIZE)
                    .forEach(cache::remove);
        }
    }

    /** 전체 5M 종가 캐시 (디버그용) */
    public Map<String, Double> getAll5mCloses() {
        return Map.copyOf(chainlink5mClose);
    }

    /** 전체 15M 종가 캐시 (디버그용) */
    public Map<String, Double> getAll15mCloses() {
        return Map.copyOf(chainlink15mClose);
    }
}
