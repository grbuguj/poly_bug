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
        builder.targetCoin(coin);
        String coinSymbol = coin + "USDT";
        // 선물 심볼: SOL/XRP도 바이난스 선물 존재
        String futuresSymbol = coinSymbol;

        // BTC/ETH는 항상 수집 (상관지표)
        try { fetchEthPrices(builder); } catch (Exception e) { log.error("ETH 가격 수집 실패: {}", e.getMessage()); }
        try { fetchBtcPrice(builder); } catch (Exception e) { log.error("BTC 가격 수집 실패: {}", e.getMessage()); }

        // 대상 코인이 BTC/ETH가 아니면 추가 수집
        if (!"BTC".equals(coin) && !"ETH".equals(coin)) {
            try { fetchCoinPrices(builder, coinSymbol); } catch (Exception e) { log.error("{} 가격 수집 실패: {}", coin, e.getMessage()); }
        } else {
            // BTC/ETH는 기존 필드에서 복사
            setCoinFieldsFromLegacy(builder, coin);
        }

        try { fetchFuturesData(builder, futuresSymbol); } catch (Exception e) { log.error("선물 데이터 수집 실패: {}", e.getMessage()); }
        try { fetchFearGreed(builder); } catch (Exception e) { log.error("공포탐욕 수집 실패: {}", e.getMessage()); }
        try { fetchTechnicals(builder, coin); } catch (Exception e) { log.error("RSI/MACD 수집 실패: {}", e.getMessage()); }
        try { fetchVolume1h(builder, coinSymbol); } catch (Exception e) { log.error("Volume 수집 실패: {}", e.getMessage()); }

        MarketIndicators indicators = builder.build();

        // BTC/ETH인 경우 coin* 필드를 legacy 필드에서 복사 (builder에서 읽기 불가하므로 post-process)
        if ("BTC".equals(coin)) {
            indicators.setCoinPrice(indicators.getBtcPrice());
            indicators.setCoinChange1h(indicators.getBtcChange1h());
            indicators.setCoinChange4h(indicators.getBtcChange4h());
            indicators.setCoinChange24h(indicators.getBtcChange24h());
            indicators.setCoinHourOpen(indicators.getBtcHourOpen());
            indicators.setCoin15mOpen(indicators.getBtc15mOpen());
            try { indicators.setCoin5mOpen(fetchCurrent5mOpen("BTCUSDT")); } catch (Exception e) { log.warn("BTC 5M 시초가 조회 실패", e); }
        } else if ("ETH".equals(coin)) {
            indicators.setCoinPrice(indicators.getEthPrice());
            indicators.setCoinChange1h(indicators.getEthChange1h());
            indicators.setCoinChange4h(indicators.getEthChange4h());
            indicators.setCoinChange24h(indicators.getEthChange24h());
            indicators.setCoinHourOpen(indicators.getEthHourOpen());
            indicators.setCoin15mOpen(indicators.getEth15mOpen());
            try { indicators.setCoin5mOpen(fetchCurrent5mOpen("ETHUSDT")); } catch (Exception e) { log.warn("ETH 5M 시초가 조회 실패", e); }
        }

        indicators.setTrend(calcTrend(indicators, coin));
        return indicators;
    }

    // ETH 현재가 + 변화율 + 현재 1H 시가
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

        // 현재 진행 중인 1H 캔들 시가
        b.ethHourOpen(fetchCurrentHourOpen("ETHUSDT"));
        // 현재 진행 중인 15M 캔들 시가
        b.eth15mOpen(fetchCurrent15mOpen("ETHUSDT"));
    }

    // BTC 현재가 + 변화율(1h/4h/24h) + 현재 1H/15M 시가
    private void fetchBtcPrice(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        String priceJson = get(BINANCE_SPOT + "/api/v3/ticker/price?symbol=BTCUSDT");
        double btcPrice = objectMapper.readTree(priceJson).path("price").asDouble();
        b.btcPrice(btcPrice);
        b.btcChange1h(fetchChangeRate("BTCUSDT", "1h"));
        b.btcChange4h(fetchChangeRate("BTCUSDT", "4h"));
        b.btcChange24h(fetchChangeRate("BTCUSDT", "1d"));

        // 현재 진행 중인 1H 캔들 시가
        b.btcHourOpen(fetchCurrentHourOpen("BTCUSDT"));
        // 현재 진행 중인 15M 캔들 시가
        b.btc15mOpen(fetchCurrent15mOpen("BTCUSDT"));
    }

    // 캔들 기반 변화율 계산 (현재 진행 중인 캔들 기준: 시가 → 현재가)
    private double fetchChangeRate(String symbol, String interval) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval + "&limit=1";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.size() < 1) return 0.0;
        JsonNode candle = arr.get(0); // 현재 진행 중인 캔들
        double open  = candle.get(1).asDouble();
        double close = candle.get(4).asDouble(); // 현재까지의 종가 = 현재가
        return open > 0 ? (close - open) / open * 100 : 0.0;
    }

    // 현재 진행 중인 1H 캔들의 시가 (정시 가격)
    private double fetchCurrentHourOpen(String symbol) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol + "&interval=1h&limit=1";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.isArray() && arr.size() > 0) {
            return arr.get(0).get(1).asDouble(); // index 1 = open price
        }
        return 0.0;
    }

    // 선물 데이터: 펀딩비, OI, 롱숏비율 (코인별)
    private void fetchFuturesData(MarketIndicators.MarketIndicatorsBuilder b) throws Exception {
        fetchFuturesData(b, "ETHUSDT");
    }

    private void fetchFuturesData(MarketIndicators.MarketIndicatorsBuilder b, String symbol) throws Exception {
        // 펀딩비
        String frJson = get(BINANCE + "/fapi/v1/fundingRate?symbol=" + symbol + "&limit=1");
        JsonNode frArr = objectMapper.readTree(frJson);
        if (frArr.isArray() && frArr.size() > 0) {
            double fr = frArr.get(0).path("fundingRate").asDouble() * 100;
            b.fundingRate(fr);
        }

        // 미결제약정
        String oiJson = get(BINANCE + "/fapi/v1/openInterest?symbol=" + symbol);
        JsonNode oiNode = objectMapper.readTree(oiJson);
        double oi = oiNode.path("openInterest").asDouble();
        b.openInterest(oi);

        // OI 변화율: 5분 전 대비 (15M용) + 30분 전 대비 (1H용)
        String oiHistJson = get(BINANCE + "/futures/data/openInterestHist?symbol=" + symbol + "&period=5m&limit=7");
        JsonNode oiHist = objectMapper.readTree(oiHistJson);
        if (oiHist.isArray() && oiHist.size() >= 2) {
            // 5분 전 대비 (15M용): 끝에서 2번째 vs 마지막
            double prev5m = oiHist.get(oiHist.size() - 2).path("sumOpenInterestValue").asDouble();
            double curr = oiHist.get(oiHist.size() - 1).path("sumOpenInterestValue").asDouble();
            b.openInterestChange5m(prev5m > 0 ? (curr - prev5m) / prev5m * 100 : 0.0);

            // 30분 전 대비 (1H용): 첫 번째 vs 마지막
            if (oiHist.size() >= 6) {
                double prev30m = oiHist.get(0).path("sumOpenInterestValue").asDouble();
                b.openInterestChange(prev30m > 0 ? (curr - prev30m) / prev30m * 100 : 0.0);
            } else {
                b.openInterestChange(prev5m > 0 ? (curr - prev5m) / prev5m * 100 : 0.0);
            }
        }

        // 롱숏비율
        String lsJson = get(BINANCE + "/futures/data/globalLongShortAccountRatio?symbol=" + symbol + "&period=1h&limit=1");
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

    // RSI + MACD 계산 (1H + 15M 둘 다) - 모든 코인 지원
    private void fetchTechnicals(MarketIndicators.MarketIndicatorsBuilder b, String coin) throws Exception {
        String symbol = coin + "USDT";

        // === 1H 캔들 기반 기술적 지표 ===
        double[] closes1h = fetchCloses(symbol, "1h", 100);
        if (closes1h.length >= 26) {
            b.rsi(calculateRSI(closes1h, 14));
            double[] macdResult = calculateMACD(closes1h, 12, 26, 9);
            b.macdLine(macdResult[0]);
            b.macdSignal(macdResult[1]);
            b.macd(macdResult[2]); // 히스토그램 = line - signal
        } else {
            log.warn("1H 봉 데이터 부족 ({}/26) - RSI/MACD 기본값", closes1h.length);
            b.rsi(50.0).macd(0.0).macdSignal(0.0).macdLine(0.0);
        }

        // === 15M 캔들 기반 기술적 지표 ===
        double[] closes15m = fetchCloses(symbol, "15m", 100);
        if (closes15m.length >= 26) {
            b.rsi15m(calculateRSI(closes15m, 14));
            double[] macd15mResult = calculateMACD(closes15m, 12, 26, 9);
            b.macdLine15m(macd15mResult[0]);
            b.macdSignal15m(macd15mResult[1]);
            b.macd15m(macd15mResult[2]);
        } else {
            log.warn("15M 봉 데이터 부족 ({}/26) - 15M RSI/MACD 기본값", closes15m.length);
            b.rsi15m(50.0).macd15m(0.0).macdSignal15m(0.0).macdLine15m(0.0);
        }
    }

    /**
     * 바이낸스에서 종가 배열 조회
     */
    private double[] fetchCloses(String symbol, String interval, int limit) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval + "&limit=" + limit;
        String json = get(url);
        JsonNode candles = objectMapper.readTree(json);
        if (!candles.isArray()) return new double[0];
        double[] closes = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            closes[i] = candles.get(i).get(4).asDouble();
        }
        return closes;
    }

    /**
     * RSI(period) 계산 - Wilder's Smoothing Method (정석)
     * 단순 SMA 시드 → 이후 지수이동평균 방식
     */
    private double calculateRSI(double[] prices, int period) {
        if (prices.length < period + 1) return 50.0;

        // 1단계: 첫 period 구간으로 초기 평균 gain/loss (SMA 시드)
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = prices[i] - prices[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss -= change;
        }
        avgGain /= period;
        avgLoss /= period;

        // 2단계: Wilder's smoothing (지수이동평균)
        for (int i = period + 1; i < prices.length; i++) {
            double change = prices[i] - prices[i - 1];
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? -change : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * MACD(fast, slow, signal) 정상 구현
     * @return [macdLine, signalLine, histogram]
     */
    private double[] calculateMACD(double[] prices, int fast, int slow, int signal) {
        if (prices.length < slow + signal) return new double[]{0.0, 0.0, 0.0};

        // 1. 각 시점의 EMA(fast)와 EMA(slow) 계산 → MACD line 시계열
        double[] emaFastArr = calculateEMASeries(prices, fast);
        double[] emaSlowArr = calculateEMASeries(prices, slow);

        // MACD line = EMA(fast) - EMA(slow), slow 시작점부터 유효
        double[] macdLines = new double[prices.length];
        for (int i = 0; i < prices.length; i++) {
            macdLines[i] = emaFastArr[i] - emaSlowArr[i];
        }

        // 2. Signal line = MACD line의 EMA(signal)
        // slow 시작점 이후의 MACD line만 사용
        int validStart = slow - 1;
        int validLen = prices.length - validStart;
        if (validLen < signal) return new double[]{macdLines[prices.length - 1], macdLines[prices.length - 1], 0.0};

        double[] validMacd = new double[validLen];
        System.arraycopy(macdLines, validStart, validMacd, 0, validLen);
        double[] signalArr = calculateEMASeries(validMacd, signal);

        double macdLine = macdLines[prices.length - 1];
        double signalLine = signalArr[signalArr.length - 1];
        double histogram = macdLine - signalLine;

        return new double[]{macdLine, signalLine, histogram};
    }

    /**
     * EMA 시계열 전체 계산 (각 시점의 EMA 값)
     */
    private double[] calculateEMASeries(double[] prices, int period) {
        double[] ema = new double[prices.length];
        double multiplier = 2.0 / (period + 1);
        ema[0] = prices[0];
        for (int i = 1; i < prices.length; i++) {
            ema[i] = (prices[i] - ema[i - 1]) * multiplier + ema[i - 1];
        }
        return ema;
    }

    // 추세 판단 로직 (coin별 독립 판단) - 모든 코인 지원
    private String calcTrend(MarketIndicators m, String coin) {
        int score = 0;

        // coin* 필드 기반 판단 (모든 코인 공통)
        if (m.getCoinChange1h() > 0.3) score++;
        if (m.getCoinChange1h() < -0.3) score--;
        if (m.getCoinChange4h() > 1.0) score++;
        if (m.getCoinChange4h() < -1.0) score--;
        if (m.getCoinChange24h() > 2.0) score++;
        if (m.getCoinChange24h() < -2.0) score--;

        // BTC 연관 보정 (BTC 자체 제외, 모든 알트코인은 BTC 영향)
        if (!"BTC".equals(coin)) {
            if (m.getBtcChange1h() > 0.5) score++;
            if (m.getBtcChange1h() < -0.5) score--;
        }

        if (score >= 2) return "UPTREND";
        if (score <= -2) return "DOWNTREND";
        return "SIDEWAYS";
    }

    /**
     * 특정 시각의 1H 캔들 시가(시초가) 조회
     * @param symbol "BTCUSDT" or "ETHUSDT"
     * @param timestampMs 해당 시각의 epoch millis (정각으로 truncate해서 사용)
     */
    public double fetchHourOpenAt(String symbol, long timestampMs) {
        try {
            // 정각으로 truncate
            long hourStart = (timestampMs / 3600000) * 3600000;
            String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol
                    + "&interval=1h&startTime=" + hourStart + "&limit=1";
            String json = get(url);
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).get(1).asDouble(); // index 1 = open price
            }
        } catch (Exception e) {
            log.warn("과거 시초가 조회 실패: {} @ {}", symbol, timestampMs, e);
        }
        return 0.0;
    }

    /**
     * 특정 시각의 15M 캔들 시가(시초가) 조회
     * @param symbol "BTCUSDT" or "ETHUSDT"
     * @param timestampMs 해당 시각의 epoch millis (15분 윈도우로 truncate)
     */
    public double fetch15mOpenAt(String symbol, long timestampMs) {
        try {
            // 15분 단위로 truncate (900000ms = 15분)
            long windowStart = (timestampMs / 900000) * 900000;
            String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol
                    + "&interval=15m&startTime=" + windowStart + "&limit=1";
            String json = get(url);
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).get(1).asDouble();
            }
        } catch (Exception e) {
            log.warn("15M 시초가 조회 실패: {} @ {}", symbol, timestampMs, e);
        }
        return 0.0;
    }

    /**
     * 현재 진행 중인 15M 캔들의 시가
     */
    public double fetchCurrent15mOpen(String symbol) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol + "&interval=15m&limit=1";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.isArray() && arr.size() > 0) {
            return arr.get(0).get(1).asDouble();
        }
        return 0.0;
    }

    /**
     * 현재 진행 중인 5M 캔들의 시가
     */
    public double fetchCurrent5mOpen(String symbol) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol + "&interval=5m&limit=1";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.isArray() && arr.size() > 0) {
            return arr.get(0).get(1).asDouble();
        }
        return 0.0;
    }

    // ===== SOL/XRP 등 범용 코인 가격 수집 =====
    private void fetchCoinPrices(MarketIndicators.MarketIndicatorsBuilder b, String symbol) throws Exception {
        // 현재가
        String priceJson = get(BINANCE_SPOT + "/api/v3/ticker/price?symbol=" + symbol);
        double price = objectMapper.readTree(priceJson).path("price").asDouble();
        b.coinPrice(price);

        // 변화율 (1h/4h/24h)
        b.coinChange1h(fetchChangeRate(symbol, "1h"));
        b.coinChange4h(fetchChangeRate(symbol, "4h"));
        b.coinChange24h(fetchChangeRate(symbol, "1d"));

        // 현재 1H/15M 캔들 시가
        b.coinHourOpen(fetchCurrentHourOpen(symbol));
        b.coin15mOpen(fetchCurrent15mOpen(symbol));
        b.coin5mOpen(fetchCurrent5mOpen(symbol));
    }

    // BTC/ETH는 기존 필드에서 coin* 필드로 복사 (하위 호환)
    private void setCoinFieldsFromLegacy(MarketIndicators.MarketIndicatorsBuilder b, String coin) {
        // builder에서는 직접 설정할 수 없으므로, build 후 별도 세팅 필요
        // 여기서는 coin 필드를 직접 설정
        if ("BTC".equals(coin)) {
            // btcPrice 등은 이미 fetchBtcPrice에서 설정됨
            // build 후 postProcess에서 복사
        } else if ("ETH".equals(coin)) {
            // ethPrice 등은 이미 fetchEthPrices에서 설정됨
        }
        // 실제 복사는 collect() 마지막에서 수행
    }

    // 1H 거래량 수집 (USDT 기준)
    private void fetchVolume1h(MarketIndicators.MarketIndicatorsBuilder b, String symbol) throws Exception {
        String url = BINANCE_SPOT + "/api/v3/klines?symbol=" + symbol + "&interval=1h&limit=1";
        String json = get(url);
        JsonNode arr = objectMapper.readTree(json);
        if (arr.isArray() && arr.size() > 0) {
            // index 7 = Quote asset volume (USDT 기준 거래량)
            double vol = arr.get(0).get(7).asDouble();
            b.volume1h(vol);
        }
    }

    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (res.body() == null) throw new RuntimeException("빈 응답: " + url);
            return res.body().string();
        }
    }
}
