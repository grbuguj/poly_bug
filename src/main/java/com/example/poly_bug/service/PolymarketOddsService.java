package com.example.poly_bug.service;

import com.example.poly_bug.config.CoinConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 폴리마켓 실시간 오즈 조회 서비스
 * 멀티코인 지원 — CoinConfig 기반
 */
@Slf4j
@Service
public class PolymarketOddsService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GAMMA = "https://gamma-api.polymarket.com";
    private static final String CLOB = "https://clob.polymarket.com";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // 범용 캐시: coinLabel → (오즈, 타임스탬프)
    private static final long CACHE_TTL_MS = 1_000;
    private final Map<String, MarketOdds> oddsCache1h = new ConcurrentHashMap<>();
    private final Map<String, Long> oddsCacheTime1h = new ConcurrentHashMap<>();
    private final Map<String, MarketOdds> oddsCache15m = new ConcurrentHashMap<>();
    private final Map<String, Long> oddsCacheTime15m = new ConcurrentHashMap<>();
    private final Map<String, MarketOdds> oddsCache5m = new ConcurrentHashMap<>();
    private final Map<String, Long> oddsCacheTime5m = new ConcurrentHashMap<>();

    // 하위 호환 빠른 접근
    private volatile MarketOdds btcCache;
    private volatile long btcCacheTime;
    private volatile MarketOdds ethCache;
    private volatile long ethCacheTime;
    private volatile MarketOdds btc15mCache;
    private volatile long btc15mCacheTime;
    private volatile MarketOdds eth15mCache;
    private volatile long eth15mCacheTime;
    private volatile MarketOdds btc5mCache;
    private volatile long btc5mCacheTime;
    private volatile MarketOdds eth5mCache;
    private volatile long eth5mCacheTime;

    /**
     * 오즈 조회 결과
     */
    public record MarketOdds(
            double upOdds,       // Up(YES) 가격 (0~1)
            double downOdds,     // Down(NO) 가격 (0~1)
            String marketId,     // conditionId
            String slug,         // 사용된 slug
            boolean available,   // 마켓 조회 성공 여부
            String yesTokenId,   // CLOB YES 토큰 ID (주문용)
            String noTokenId     // CLOB NO 토큰 ID (주문용)
    ) {
        /** 하위 호환 생성자 (토큰 ID 없이) */
        public MarketOdds(double upOdds, double downOdds, String marketId, String slug, boolean available) {
            this(upOdds, downOdds, marketId, slug, available, null, null);
        }
    }

    public MarketOdds getBtcOdds() {
        long now = System.currentTimeMillis();
        if (btcCache != null && (now - btcCacheTime) < CACHE_TTL_MS) {
            return btcCache;
        }
        MarketOdds odds = getOdds("bitcoin", "BTC");
        btcCache = odds;
        btcCacheTime = now;
        return odds;
    }

    public MarketOdds getEthOdds() {
        long now = System.currentTimeMillis();
        if (ethCache != null && (now - ethCacheTime) < CACHE_TTL_MS) {
            return ethCache;
        }
        MarketOdds odds = getOdds("ethereum", "ETH");
        ethCache = odds;
        ethCacheTime = now;
        return odds;
    }

    /**
     * 범용 코인 1H 오즈 조회 (CoinConfig 기반)
     * OddsLagDetector 등에서 모든 코인 동적 조회용
     */
    public MarketOdds getOddsForCoin(String coinLabel) {
        // BTC/ETH는 기존 캐시 활용
        if ("BTC".equals(coinLabel)) return getBtcOdds();
        if ("ETH".equals(coinLabel)) return getEthOdds();

        long now = System.currentTimeMillis();
        Long cached = oddsCacheTime1h.get(coinLabel);
        if (cached != null && (now - cached) < CACHE_TTL_MS) {
            MarketOdds c = oddsCache1h.get(coinLabel);
            if (c != null) return c;
        }

        CoinConfig.CoinDef coinDef = CoinConfig.getByLabel(coinLabel);
        if (coinDef == null) return new MarketOdds(0.5, 0.5, "unknown", "", false);

        MarketOdds odds = getOdds(coinDef.slug(), coinLabel);
        oddsCache1h.put(coinLabel, odds);
        oddsCacheTime1h.put(coinLabel, now);
        return odds;
    }

    /**
     * 범용 코인 15M 오즈 조회
     */
    public MarketOdds getOdds15mForCoin(String coinLabel) {
        if ("BTC".equals(coinLabel)) return getBtcOdds15m();
        if ("ETH".equals(coinLabel)) return getEthOdds15m();

        long now = System.currentTimeMillis();
        Long cached = oddsCacheTime15m.get(coinLabel);
        if (cached != null && (now - cached) < CACHE_TTL_MS) {
            MarketOdds c = oddsCache15m.get(coinLabel);
            if (c != null) return c;
        }

        CoinConfig.CoinDef coinDef = CoinConfig.getByLabel(coinLabel);
        if (coinDef == null) return new MarketOdds(0.5, 0.5, "unknown", "", false);

        // 15M slug prefix: 소문자 라벨 (sol, doge, xrp...)
        String prefix15m = coinLabel.toLowerCase();
        MarketOdds odds = getOdds15m(prefix15m, coinLabel);
        oddsCache15m.put(coinLabel, odds);
        oddsCacheTime15m.put(coinLabel, now);
        return odds;
    }

    // ===== 15M 오즈 =====
    public MarketOdds getBtcOdds15m() {
        long now = System.currentTimeMillis();
        if (btc15mCache != null && (now - btc15mCacheTime) < CACHE_TTL_MS) {
            return btc15mCache;
        }
        MarketOdds odds = getOdds15m("btc", "BTC");
        btc15mCache = odds;
        btc15mCacheTime = now;
        return odds;
    }

    public MarketOdds getEthOdds15m() {
        long now = System.currentTimeMillis();
        if (eth15mCache != null && (now - eth15mCacheTime) < CACHE_TTL_MS) {
            return eth15mCache;
        }
        MarketOdds odds = getOdds15m("eth", "ETH");
        eth15mCache = odds;
        eth15mCacheTime = now;
        return odds;
    }

    /**
     * 15M 마켓 오즈 조회
     * slug 패턴: btc-updown-15m-{unix_timestamp} (15분 윈도우 시작 시점)
     */
    private MarketOdds getOdds15m(String coinPrefix, String coinLabel) {
        try {
            String slug = build15mSlug(coinPrefix);
            log.info("[{} 15M] 마켓 slug: {}", coinLabel, slug);

            // Gamma events API로 검색
            MarketOdds result = fetchFromGammaEvents(slug, coinLabel + " 15M");
            if (result != null && result.available) return result;

            // fallback: 직접 slug
            result = fetchFromGammaMarketSlug(slug, coinLabel + " 15M");
            if (result != null && result.available) return result;

            log.warn("[{} 15M] 마켓 조회 실패", coinLabel);
            return new MarketOdds(0.5, 0.5, "unavailable", slug, false);
        } catch (Exception e) {
            log.error("[{} 15M] 오즈 조회 오류: {}", coinLabel, e.getMessage());
            return new MarketOdds(0.5, 0.5, "error", "", false);
        }
    }

    /**
     * 현재 15분 윈도우의 slug 생성
     * 예: btc-updown-15m-1770942600
     * timestamp = 현재 15분 윈도우 시작의 unix epoch (초)
     */
    String build15mSlug(String coinPrefix) {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int windowStart = (minute / 15) * 15; // 0, 15, 30, 45
        ZonedDateTime windowStartTime = nowET.withMinute(windowStart).withSecond(0).withNano(0);
        long epochSecond = windowStartTime.toEpochSecond();
        return String.format("%s-updown-15m-%d", coinPrefix, epochSecond);
    }

    // =========================================================================
    // ⭐ 5M 오즈 조회
    // =========================================================================

    public MarketOdds getOdds5mForCoin(String coinLabel) {
        if ("BTC".equals(coinLabel)) return getBtcOdds5m();
        if ("ETH".equals(coinLabel)) return getEthOdds5m();

        long now = System.currentTimeMillis();
        Long cached = oddsCacheTime5m.get(coinLabel);
        if (cached != null && (now - cached) < CACHE_TTL_MS) {
            MarketOdds c = oddsCache5m.get(coinLabel);
            if (c != null) return c;
        }

        CoinConfig.CoinDef coinDef = CoinConfig.getByLabel(coinLabel);
        if (coinDef == null) return new MarketOdds(0.5, 0.5, "unknown", "", false);

        String prefix5m = coinLabel.toLowerCase();
        MarketOdds odds = getOdds5m(prefix5m, coinLabel);
        oddsCache5m.put(coinLabel, odds);
        oddsCacheTime5m.put(coinLabel, now);
        return odds;
    }

    public MarketOdds getBtcOdds5m() {
        long now = System.currentTimeMillis();
        if (btc5mCache != null && (now - btc5mCacheTime) < CACHE_TTL_MS) {
            return btc5mCache;
        }
        MarketOdds odds = getOdds5m("btc", "BTC");
        btc5mCache = odds;
        btc5mCacheTime = now;
        return odds;
    }

    public MarketOdds getEthOdds5m() {
        long now = System.currentTimeMillis();
        if (eth5mCache != null && (now - eth5mCacheTime) < CACHE_TTL_MS) {
            return eth5mCache;
        }
        MarketOdds odds = getOdds5m("eth", "ETH");
        eth5mCache = odds;
        eth5mCacheTime = now;
        return odds;
    }

    /**
     * 5M 마켓 오즈 조회
     * slug 패턴: btc-updown-5m-{unix_timestamp} (5분 윈도우 시작 시점)
     */
    private MarketOdds getOdds5m(String coinPrefix, String coinLabel) {
        try {
            String slug = build5mSlug(coinPrefix);
            log.info("[{} 5M] 마켓 slug: {}", coinLabel, slug);

            MarketOdds result = fetchFromGammaEvents(slug, coinLabel + " 5M");
            if (result != null && result.available) return result;

            result = fetchFromGammaMarketSlug(slug, coinLabel + " 5M");
            if (result != null && result.available) return result;

            log.warn("[{} 5M] 마켓 조회 실패", coinLabel);
            return new MarketOdds(0.5, 0.5, "unavailable", slug, false);
        } catch (Exception e) {
            log.error("[{} 5M] 오즈 조회 오류: {}", coinLabel, e.getMessage());
            return new MarketOdds(0.5, 0.5, "error", "", false);
        }
    }

    /**
     * 현재 5분 윈도우의 slug 생성
     * 예: btc-updown-5m-1771122000
     * timestamp = 현재 5분 윈도우 시작의 unix epoch (초)
     */
    public String build5mSlug(String coinPrefix) {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int windowStart = (minute / 5) * 5; // 0, 5, 10, 15, ...55
        ZonedDateTime windowStartTime = nowET.withMinute(windowStart).withSecond(0).withNano(0);
        long epochSecond = windowStartTime.toEpochSecond();
        return String.format("%s-updown-5m-%d", coinPrefix, epochSecond);
    }

    private MarketOdds getOdds(String coinSlug, String coinLabel) {
        try {
            // 1. 현재 ET 시간 기준 slug 생성
            String slug = buildCurrentSlug(coinSlug);
            log.info("[{}] 마켓 slug: {}", coinLabel, slug);

            // 2. Gamma API - events?slug= 로 마켓 검색
            MarketOdds result = fetchFromGammaEvents(slug, coinLabel);
            if (result != null && result.available) {
                return result;
            }

            // 3. 실패 시 Gamma API - markets/slug/ 로 직접 시도
            result = fetchFromGammaMarketSlug(slug, coinLabel);
            if (result != null && result.available) {
                return result;
            }

            // 4. 최종 실패 시 events 검색으로 대체
            result = searchByTitle(coinSlug, coinLabel);
            if (result != null && result.available) {
                return result;
            }

            log.warn("[{}] 마켓 조회 실패 - 기본값 0.5/0.5", coinLabel);
            return new MarketOdds(0.5, 0.5, "unavailable", slug, false);

        } catch (Exception e) {
            log.error("[{}] 오즈 조회 오류: {}", coinLabel, e.getMessage());
            return new MarketOdds(0.5, 0.5, "error", "", false);
        }
    }

    /**
     * 현재 ET 시간 기준으로 활성 마켓 slug 생성
     * 예: bitcoin-up-or-down-february-12-4am-et
     */
    String buildCurrentSlug(String coinSlug) {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int hour = nowET.getHour();
        int day = nowET.getDayOfMonth();
        String month = nowET.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase();

        // 12시간제 변환
        String ampm;
        int hour12;
        if (hour == 0) {
            hour12 = 12;
            ampm = "am";
        } else if (hour < 12) {
            hour12 = hour;
            ampm = "am";
        } else if (hour == 12) {
            hour12 = 12;
            ampm = "pm";
        } else {
            hour12 = hour - 12;
            ampm = "pm";
        }

        return String.format("%s-up-or-down-%s-%d-%d%s-et",
                coinSlug, month, day, hour12, ampm);
    }

    /**
     * 방법 1: Gamma Events API - slug으로 이벤트 검색
     */
    private MarketOdds fetchFromGammaEvents(String slug, String coinLabel) {
        try {
            String url = GAMMA + "/events?slug=" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return null;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray() || events.isEmpty()) {
                log.debug("[{}] events?slug={} → 결과 없음", coinLabel, slug);
                return null;
            }

            JsonNode event = events.get(0);
            JsonNode markets = event.path("markets");
            if (!markets.isArray() || markets.isEmpty()) {
                log.debug("[{}] 이벤트에 마켓 없음", coinLabel);
                return null;
            }

            // 첫 번째 마켓에서 데이터 추출
            JsonNode market = markets.get(0);
            return extractOddsFromMarket(market, slug, coinLabel);

        } catch (Exception e) {
            log.debug("[{}] Gamma events 조회 실패: {}", coinLabel, e.getMessage());
            return null;
        }
    }

    /**
     * 방법 2: Gamma Markets API - slug으로 직접 마켓 조회
     */
    private MarketOdds fetchFromGammaMarketSlug(String slug, String coinLabel) {
        try {
            String url = GAMMA + "/markets/slug/" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return null;

            JsonNode market = objectMapper.readTree(json);
            if (market.has("error") || market.has("statusCode")) return null;

            return extractOddsFromMarket(market, slug, coinLabel);

        } catch (Exception e) {
            log.debug("[{}] Gamma markets/slug 조회 실패: {}", coinLabel, e.getMessage());
            return null;
        }
    }

    /**
     * 방법 3: 제목 검색으로 마켓 찾기 (fallback)
     */
    private MarketOdds searchByTitle(String coinSlug, String coinLabel) {
        try {
            ZonedDateTime nowET = ZonedDateTime.now(ET);
            int hour = nowET.getHour();
            String ampm = hour < 12 ? "AM" : "PM";
            int hour12 = hour == 0 ? 12 : (hour > 12 ? hour - 12 : hour);

            // 검색어: "Bitcoin Up or Down" + 날짜/시간
            String searchTerm = coinSlug.substring(0, 1).toUpperCase() + coinSlug.substring(1)
                    + " Up or Down";

            String url = GAMMA + "/events?closed=false&limit=20&title=" + searchTerm;
            String json = httpGet(url);
            if (json == null) return null;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray()) return null;

            // 현재 시간대에 맞는 이벤트 찾기
            String hourPattern = hour12 + ampm;
            for (JsonNode event : events) {
                String title = event.path("title").asText("");
                if (title.contains(hourPattern) || title.contains(hour12 + ampm.toLowerCase())) {
                    JsonNode markets = event.path("markets");
                    if (markets.isArray() && !markets.isEmpty()) {
                        String foundSlug = event.path("slug").asText("search");
                        return extractOddsFromMarket(markets.get(0), foundSlug, coinLabel);
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("[{}] 제목 검색 실패: {}", coinLabel, e.getMessage());
            return null;
        }
    }

    /**
     * 마켓 JSON에서 오즈 추출
     * - clobTokenIds가 있으면 CLOB API로 실시간 가격 조회 (우선 — 실제 매수가)
     * - 실패 시 outcomePrices 사용 (Gamma 스냅샷)
     */
    private MarketOdds extractOddsFromMarket(JsonNode market, String slug, String coinLabel) {
        String conditionId = market.path("conditionId").asText(
                market.path("condition_id").asText("unknown"));

        // 토큰 ID 파싱 (주문 실행용)
        String yesTokenId = null;
        String noTokenId = null;
        String tokenIdsStr = market.path("clobTokenIds").asText("");
        if (!tokenIdsStr.isBlank()) {
            try {
                JsonNode tokenIds = objectMapper.readTree(tokenIdsStr);
                if (tokenIds.isArray() && tokenIds.size() >= 2) {
                    yesTokenId = tokenIds.get(0).asText();
                    noTokenId = tokenIds.get(1).asText();
                }
            } catch (Exception e) {
                log.debug("[{}] clobTokenIds 파싱 실패: {}", coinLabel, e.getMessage());
            }
        }

        // 1차: CLOB API로 실시간 가격 조회 (양쪽 독립 — 실제 매수가, 스프레드 포함)
        if (yesTokenId != null && noTokenId != null) {
            MarketOdds clobResult = fetchFromClob(yesTokenId, noTokenId, conditionId, slug, coinLabel);
            if (clobResult != null) {
                // CLOB 결과에 토큰 ID 포함해서 반환
                return new MarketOdds(clobResult.upOdds(), clobResult.downOdds(),
                        conditionId, slug, true, yesTokenId, noTokenId);
            }
        }

        // 2차: outcomePrices 사용 (Gamma 스냅샷 — CLOB 실패 시 fallback)
        String outcomePricesStr = market.path("outcomePrices").asText("");
        if (!outcomePricesStr.isBlank()) {
            try {
                JsonNode prices = objectMapper.readTree(outcomePricesStr);
                if (prices.isArray() && prices.size() >= 2) {
                    double upOdds = prices.get(0).asDouble(0.5);
                    double downOdds = prices.get(1).asDouble(0.5);
                    log.info("✅ [{}] 오즈(Gamma스냅샷) — Up: {}¢ / Down: {}¢ | slug: {}",
                            coinLabel,
                            String.format("%.0f", upOdds * 100),
                            String.format("%.0f", downOdds * 100),
                            slug);
                    return new MarketOdds(upOdds, downOdds, conditionId, slug, true, yesTokenId, noTokenId);
                }
            } catch (Exception e) {
                log.debug("[{}] outcomePrices 파싱 실패: {}", coinLabel, e.getMessage());
            }
        }

        log.warn("[{}] 마켓에서 가격 정보 없음: {}", coinLabel, conditionId);
        return null;
    }

    /**
     * CLOB API로 실시간 가격 조회
     */
    private MarketOdds fetchFromClob(String upTokenId, String downTokenId,
                                      String conditionId, String slug, String coinLabel) {
        try {
            // Up(YES) 가격
            String upUrl = CLOB + "/price?token_id=" + upTokenId + "&side=BUY";
            String upJson = httpGet(upUrl);
            double upOdds = 0.5;
            if (upJson != null) {
                JsonNode upNode = objectMapper.readTree(upJson);
                upOdds = upNode.path("price").asDouble(0.5);
            }

            double downOdds = 1.0 - upOdds;

            // Down도 별도 조회해서 검증 (선택)
            try {
                String downUrl = CLOB + "/price?token_id=" + downTokenId + "&side=BUY";
                String downJson = httpGet(downUrl);
                if (downJson != null) {
                    JsonNode downNode = objectMapper.readTree(downJson);
                    double downDirect = downNode.path("price").asDouble(-1);
                    if (downDirect > 0) {
                        downOdds = downDirect;
                    }
                }
            } catch (Exception ignored) {}

            log.info("✅ [{}] 오즈(CLOB) — Up: {}% / Down: {}% | slug: {}",
                    coinLabel,
                    String.format("%.1f", upOdds * 100),
                    String.format("%.1f", downOdds * 100),
                    slug);
            return new MarketOdds(upOdds, downOdds, conditionId, slug, true);

        } catch (Exception e) {
            log.error("[{}] CLOB 가격 조회 실패: {}", coinLabel, e.getMessage());
            return null;
        }
    }

    private String httpGet(String url) {
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    log.debug("HTTP {} for {}", res.code(), url);
                    return null;
                }
                return res.body().string();
            }
        } catch (Exception e) {
            log.debug("HTTP 요청 실패 {}: {}", url, e.getMessage());
            return null;
        }
    }
}
