package com.example.poly_bug.init;

import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.repository.TradeRepository;
import com.example.poly_bug.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 서버 시작 시 openPrice가 null인 과거 trades에 시초가를 채워넣음.
 * Binance 1H 캔들 API로 해당 시각의 정시 시가를 조회.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenPriceBackfillInitializer {

    private final TradeRepository tradeRepository;
    private final MarketDataService marketDataService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOpenPrices() {
        List<Trade> trades = tradeRepository.findTradesWithNullOpenPrice();
        if (trades.isEmpty()) {
            log.info("[Backfill] openPrice 채울 trade 없음 — 스킵");
            return;
        }

        log.info("[Backfill] openPrice null인 {} 건 백필 시작", trades.size());
        int updated = 0;

        for (Trade trade : trades) {
            try {
                String symbol = "BTC".equals(trade.getCoin()) ? "BTCUSDT" : "ETHUSDT";
                long createdMs = trade.getCreatedAt()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();

                String tf = trade.getTimeframe() != null ? trade.getTimeframe() : "1H";
                double openPrice;
                if ("15M".equals(tf)) {
                    openPrice = marketDataService.fetch15mOpenAt(symbol, createdMs);
                } else {
                    openPrice = marketDataService.fetchHourOpenAt(symbol, createdMs);
                }

                if (openPrice > 0) {
                    trade.setOpenPrice(openPrice);
                    tradeRepository.save(trade);
                    updated++;
                    log.debug("[Backfill] Trade #{} {} {} → openPrice ${}", 
                            trade.getId(), trade.getCoin(), trade.getCreatedAt(), openPrice);
                }

                // Binance rate limit 방지 (100ms 간격)
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("[Backfill] Trade #{} 실패: {}", trade.getId(), e.getMessage());
            }
        }

        log.info("[Backfill] 완료 — {}/{} 건 openPrice 채움", updated, trades.size());
    }
}
