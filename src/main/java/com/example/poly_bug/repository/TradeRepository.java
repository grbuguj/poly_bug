package com.example.poly_bug.repository;

import com.example.poly_bug.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findTop20ByOrderByCreatedAtDesc();

    List<Trade> findTop50ByOrderByCreatedAtDesc();

    // coin별 조회
    List<Trade> findTop20ByCoinOrderByCreatedAtDesc(String coin);

    List<Trade> findTop50ByCoinOrderByCreatedAtDesc(String coin);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.coin = :coin")
    Long countWinsByCoin(String coin);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.coin = :coin")
    Long countResolvedByCoin(String coin);

    @Query("SELECT COALESCE(SUM(t.profitLoss), 0) FROM Trade t WHERE t.coin = :coin AND t.profitLoss IS NOT NULL")
    Double totalProfitLossByCoin(String coin);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN'")
    Long countWins();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING'")
    Long countResolved();

    @Query("SELECT COALESCE(SUM(t.profitLoss), 0) FROM Trade t WHERE t.profitLoss IS NOT NULL")
    Double totalProfitLoss();

    // 패턴 분석: 펀딩비 양수일 때 WIN 건수
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.fundingRate > 0")
    Long countWinsWithPositiveFunding();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.fundingRate > 0")
    Long countResolvedWithPositiveFunding();

    // 패턴 분석: 추세별 승률
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result = 'WIN' AND t.marketTrend = :trend")
    Long countWinsByTrend(String trend);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.result != 'PENDING' AND t.marketTrend = :trend")
    Long countResolvedByTrend(String trend);
}
