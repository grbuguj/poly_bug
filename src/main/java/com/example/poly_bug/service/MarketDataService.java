package com.example.poly_bug.service;

import com.example.poly_bug.dto.MarketIndicators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MarketDataService {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BINANCE = "https://fapi.binance.com";
    private static final String BINANCE_SPOT = "https://api.binance.com";
    private static final String FEAR_GREED = "https://api.alternative.me/fng/?limit=1";

    /**
     * 1시간 BTC/ETH 예측에 필요한 모든 지표 수집
     */
    public MarketIndicators collect() {
        return collect("ETH");
    }

    public MarketIndicators collect(String coin) {
        MarketIndicators.MarketIndicatorsBuilder builder = MarketIndicators.builder();

        try { fetchEthPrices(builder); } catch (Exception e) { log.error("ETH 가격 수집 실패: {}", e.getMessage()); }
        try { fetchBtcPrice(builder); } catch (Exception e) { log.error("BTC 가격 수집 실패: {}", e.getMessage()); }
        try { fetchFuturesData(builder); } catch (Exception e) { log.error("선물 데이터 수집 실패: {}", e.getMessage()); }
        try { fetchFearGreed(builder); } catch (Exception e) { log.error("공포탐욕 수집 실패: {}", e.getMessage()); }

        MarketIndicators indicators = builder.build();
        indicators.setTrend(calcTrend(indicators));
        return indicators;
    }

    // ETH 현재가 + 변화율
    private void fetchEthPrices(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        // 현재가
        String priceJson = get(BINANCE_SPOT + "/api/v3/ticker/price?symbol=ETHUSDT");
        JsonNode priceNode = objectMapper.readTree(priceJson);
        double ethPrice = priceNode.path("price").asDouble();
        b.ethPrice(ethPrice);

        // 1h, 4h, 24h 캔들로 변화율 계산
        b.ethChange1h(fetchChangeRate("ETHUSDT", "1h"));
        b.ethChange4h(fetchChangeRate("ETHUSDT", "4h"));
        b.ethChange24h(fetchChangeRate("ETHUSDT", "1d"));
    }

    // BTC 현재가 + 1시간 변화율
    private void fetchBtcPrice(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        String priceJson = get(BINANCE_SPOT + "/api/v3/ticker/price?symbol=BTCUSDT");
        double btcPrice = objectMapper.readTree(priceJson).path("price").asDouble();
        b.btcPrice(btcPrice);
        b.btcChange1h(fetchChangeRate("BTCUSDT", "1h"));
    }

    // 캔들 기반 변화율 계산 (직전 완성 캔들 기준)
    private double fetchChangeRate(String symbol, String interval) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval + "&limit=2";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.size() < 2) return 0.0;
        JsonNode candle = arr.get(arr.size() - 2); // 직전 완성 캔들
        double open  = candle.get(1).asDouble();
        double close = candle.get(4).asDouble();
        return open > 0 ? (close - open) / open * 100 : 0.0;
    }

    // 선물 데이터: 펀딩비, OI, 롱숏비율
    private void fetchFuturesData(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        // 펀딩비
        String frJson = get(BINANCE + "/fapi/v1/fundingRate?symbol=ETHUSDT&limit=1");
        JsonNode frArr = objectMapper.readTree(frJson);
        if (frArr.isArray() && frArr.size() > 0) {
            double fr = frArr.get(0).path("fundingRate").asDouble() * 100;
            b.fundingRate(fr);
        }

        // 미결제약정
        String oiJson = get(BINANCE + "/fapi/v1/openInterest?symbol=ETHUSDT");
        JsonNode oiNode = objectMapper.readTree(oiJson);
        double oi = oiNode.path("openInterest").asDouble();
        b.openInterest(oi);

        // OI 변화율 (5분 전 대비)
        String oiHistJson = get(BINANCE + "/futures/data/openInterestHist?symbol=ETHUSDT&period=5m&limit=2");
        JsonNode oiHist = objectMapper.readTree(oiHistJson);
        if (oiHist.isArray() && oiHist.size() >= 2) {
            double prev = oiHist.get(0).path("sumOpenInterestValue").asDouble();
            double curr = oiHist.get(1).path("sumOpenInterestValue").asDouble();
            b.openInterestChange(prev > 0 ? (curr - prev) / prev * 100 : 0.0);
        }

        // 롱숏비율
        String lsJson = get(BINANCE + "/futures/data/globalLongShortAccountRatio?symbol=ETHUSDT&period=1h&limit=1");
        JsonNode lsArr = objectMapper.readTree(lsJson);
        if (lsArr.isArray() && lsArr.size() > 0) {
            b.longShortRatio(lsArr.get(0).path("longShortRatio").asDouble());
        }
    }

    // 공포탐욕지수
    private void fetchFearGreed(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        String json = get(FEAR_GREED);
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.path("data");
        if (data.isArray() && data.size() > 0) {
            int value = data.get(0).path("value").asInt();
            String label = toKoreanLabel(value);
            b.fearGreedIndex(value);
            b.fearGreedLabel(label);
        }
    }

    private String toKoreanLabel(int value) {
        if (value <= 20) return "극도 공포";
        if (value <= 40) return "공포";
        if (value <= 60) return "중립";
        if (value <= 80) return "탐욕";
        return "극도 탐욕";
    }

    // 추세 판단 로직
    private String calcTrend(MarketIndicators m) {
        int score = 0;
        if (m.getEthChange1h() > 0.3) score++;
        if (m.getEthChange1h() < -0.3) score--;
        if (m.getEthChange4h() > 1.0) score++;
        if (m.getEthChange4h() < -1.0) score--;
        if (m.getBtcChange1h() > 0.3) score++;
        if (m.getBtcChange1h() < -0.3) score--;

        if (score >= 2) return "UPTREND";
        if (score <= -2) return "DOWNTREND";
        return "SIDEWAYS";
    }

    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답: " + url);
            return res.body().string();
        }
    }
}
