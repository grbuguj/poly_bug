package com.example.poly_bug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

/**
 * 폴리마켓 CLOB API - 실시간 오즈(가격) 조회
 * 인증 없이 공개 엔드포인트로 조회 가능
 */
@Slf4j
@Service
public class PolymarketOddsService {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CLOB = "https://clob.polymarket.com";
    private static final String GAMMA = "https://gamma-api.polymarket.com";

    // 폴리마켓 ETH 1H Up/Down 마켓 토큰 ID (실제 토큰 ID로 교체 필요)
    // https://polymarket.com 에서 마켓 찾아서 slug로 조회
    private static final String ETH_1H_SLUG = "eth-up-or-down-1-hour";
    private static final String BTC_1H_SLUG = "btc-up-or-down-1-hour";

    /**
     * 현재 오즈 조회 결과
     */
    public record MarketOdds(
            double upOdds,       // Up(YES) 현재 오즈 (0~1)
            double downOdds,     // Down(NO) 현재 오즈 (0~1)
            String marketId,
            boolean available    // 마켓 존재 여부
    ) {}

    /**
     * ETH 1H 오즈 조회
     */
    public MarketOdds getEthOdds() {
        return getOdds(ETH_1H_SLUG, "ETH");
    }

    /**
     * BTC 1H 오즈 조회
     */
    public MarketOdds getBtcOdds() {
        return getOdds(BTC_1H_SLUG, "BTC");
    }

    private MarketOdds getOdds(String slug, String coin) {
        try {
            // 1. Gamma API로 현재 활성 마켓 검색
            String searchUrl = GAMMA + "/markets?tag_id=&closed=false&limit=50&slug=" + slug;
            // slug 직접 검색이 안 되면 이벤트 검색으로 대체
            String eventsUrl = GAMMA + "/events?closed=false&limit=100";
            String json = get(eventsUrl);
            JsonNode events = objectMapper.readTree(json);

            // 코인명 포함된 1시간 마켓 찾기
            for (JsonNode event : events) {
                String title = event.path("title").asText("").toLowerCase();
                if (title.contains(coin.toLowerCase()) && title.contains("1 hour")) {
                    JsonNode markets = event.path("markets");
                    if (markets.isArray() && markets.size() > 0) {
                        JsonNode market = markets.get(0);
                        String tokenId = getUpTokenId(market);
                        if (tokenId != null) {
                            return fetchOddsFromClob(tokenId, market.path("conditionId").asText());
                        }
                    }
                }
            }

            log.warn("{} 1H 마켓 못 찾음 - 기본값 0.5 사용", coin);
            return new MarketOdds(0.5, 0.5, "unknown", false);

        } catch (Exception e) {
            log.error("{} 오즈 조회 실패: {}", coin, e.getMessage());
            return new MarketOdds(0.5, 0.5, "error", false);
        }
    }

    private String getUpTokenId(JsonNode market) {
        JsonNode tokens = market.path("clobTokenIds");
        if (tokens.isArray() && tokens.size() > 0) {
            return tokens.get(0).asText();
        }
        return null;
    }

    private MarketOdds fetchOddsFromClob(String tokenId, String marketId) throws Exception {
        String url = CLOB + "/price?token_id=" + tokenId + "&side=BUY";
        String json = get(url);
        JsonNode node = objectMapper.readTree(json);
        double upOdds = node.path("price").asDouble(0.5);
        double downOdds = 1.0 - upOdds;
        log.info("오즈 조회 성공 - Up: {}, Down: {}", upOdds, downOdds);
        return new MarketOdds(upOdds, downOdds, marketId, true);
    }

    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답");
            return res.body().string();
        }
    }
}
