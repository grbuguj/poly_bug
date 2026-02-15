package com.example.poly_bug.repository;

import com.example.poly_bug.entity.Trade;
import com.example.poly_bug.entity.Trade.TradeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findTop20ByOrderByCreatedAtDesc();

    List<Trade> findTop50ByOrderByCreatedAtDesc();

    // coin별 조회 (HOLD 제외 — 대시보드용)
    List<Trade> findTop20ByCoinAndActionNotOrderByCreatedAtDesc(String coin, Trade.TradeAction action);

    // coin + timeframe 조회 (HOLD 제외)
    List<Trade> findTop20ByCoinAndTimeframeAndActionNotOrderByCreatedAtDesc(String coin, String timeframe, Trade.TradeAction action);

    // coin + timeframe 조회 (HOLD 포함 - 대시보드 기록용)
    @Query("SELECT t FROM Trade t WHERE t.coin = :coin " +
           "AND (t.timeframe = :timeframe OR (:timeframe = '1H' AND t.timeframe IS NULL)) " +
           "ORDER BY t.createdAt DESC LIMIT 20")
    List<Trade> findTop20ByCoinAndTimeframeIncludingLegacy(@Param("coin") String coin, @Param("timeframe") String timeframe);

    // coin별 조회 (전체 — 승률 계산용)
    List<Trade> findTop20ByCoinOrderByCreatedAtDesc(String coin);

    List<Trade> findTop50ByCoinOrderByCreatedAtDesc(String coin);

    // 전체 조회 (HOLD 제외 — 대시보드용)
    List<Trade> findTop20ByActionNotOrderByCreatedAtDesc(Trade.TradeAction action);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.coin = :coin AND t.action != 'HOLD'")
    Long countWinsByCoin(String coin);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.coin = :coin AND t.action != 'HOLD'")
    Long countResolvedByCoin(String coin);

    @Query("SELECT COALESCE(SUM(t.profitLoss), 0) FROM Trade t WHERE t.coin = :coin AND t.profitLoss IS NOT NULL AND t.action != 'HOLD'")
    Double totalProfitLossByCoin(String coin);

    // 결과 상태별 조회
    List<Trade> findByResult(TradeResult result);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.action != 'HOLD'")
    Long countWins();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.action != 'HOLD'")
    Long countResolved();

    // HOLD 제외 실제 배팅 수
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.action != 'HOLD'")
    Long countActualBets();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.action != 'HOLD' AND t.coin = :coin")
    Long countActualBetsByCoin(String coin);

    @Query("SELECT COALESCE(SUM(t.profitLoss), 0) FROM Trade t WHERE t.profitLoss IS NOT NULL AND t.action != 'HOLD'")
    Double totalProfitLoss();

    // 패턴 분석: 펀딩비 양수일 때 WIN 건수
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.fundingRate > 0")
    Long countWinsWithPositiveFunding();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.result != 'HOLD' AND t.fundingRate > 0 AND t.action != 'HOLD'")
    Long countResolvedWithPositiveFunding();

    // 패턴 분석: 추세별 승률
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.marketTrend = :trend")
    Long countWinsByTrend(String trend);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.marketTrend = :trend")
    Long countResolvedByTrend(String trend);

    // openPrice가 null인 trades (backfill용)
    @Query("SELECT t FROM Trade t WHERE t.openPrice IS NULL AND t.action != 'HOLD'")
    List<Trade> findTradesWithNullOpenPrice();

    // coin + timeframe별 최근 50건 (Claude 프롬프트 과거 성적용)
    @Query("SELECT t FROM Trade t WHERE t.coin = :coin AND t.action != 'HOLD' " +
           "AND (t.timeframe = :timeframe OR (:timeframe = '1H' AND t.timeframe IS NULL)) " +
           "ORDER BY t.createdAt DESC LIMIT 50")
    List<Trade> findTop50ByCoinAndTimeframeForStats(@Param("coin") String coin, @Param("timeframe") String timeframe);

    // ===== Level 1: 조건별 승률 매트릭스 쿼리 =====

    // 펀딩비 양수 + WIN
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.fundingRate > 0 AND t.coin = :coin AND t.action != 'HOLD' AND t.result != 'PENDING'")
    Long countWinsWithPositiveFundingByCoin(@Param("coin") String coin);
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.fundingRate > 0 AND t.coin = :coin AND t.action != 'HOLD' AND t.result != 'PENDING'")
    Long countResolvedWithPositiveFundingByCoin(@Param("coin") String coin);

    // 펀딩비 음수 + WIN
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.fundingRate < 0 AND t.coin = :coin AND t.action != 'HOLD' AND t.result != 'PENDING'")
    Long countWinsWithNegativeFundingByCoin(@Param("coin") String coin);
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.fundingRate < 0 AND t.coin = :coin AND t.action != 'HOLD' AND t.result != 'PENDING'")
    Long countResolvedWithNegativeFundingByCoin(@Param("coin") String coin);

    // 추세별 승률 (coin 필터)
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.marketTrend = :trend AND t.coin = :coin AND t.action != 'HOLD'")
    Long countWinsByTrendAndCoin(@Param("trend") String trend, @Param("coin") String coin);
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.marketTrend = :trend AND t.coin = :coin AND t.action != 'HOLD'")
    Long countResolvedByTrendAndCoin(@Param("trend") String trend, @Param("coin") String coin);

    // 최근 N건 반성 포함 트레이드 (Level 3)
    @Query("SELECT t FROM Trade t WHERE t.reflection IS NOT NULL AND t.reflection != '' " +
           "AND t.result != 'PENDING' ORDER BY t.createdAt DESC LIMIT :n")
    List<Trade> findRecentReflectedTrades(@Param("n") int n);

    // 연속 결과 분석용: 최근 결과 순서대로
    @Query("SELECT t FROM Trade t WHERE t.coin = :coin AND t.action != 'HOLD' AND t.result != 'PENDING' " +
           "ORDER BY t.createdAt DESC LIMIT 10")
    List<Trade> findRecent10ResolvedByCoin(@Param("coin") String coin);
}
