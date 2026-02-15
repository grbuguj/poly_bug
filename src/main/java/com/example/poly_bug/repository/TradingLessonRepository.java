package com.example.poly_bug.repository;

import com.example.poly_bug.entity.TradingLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TradingLessonRepository extends JpaRepository<TradingLesson, Long> {

    // 중요도 높은 순으로 최대 7개
    List<TradingLesson> findTop7ByOrderByImportanceDesc();

    // 전체 교훈 (갱신용)
    List<TradingLesson> findAllByOrderByImportanceDesc();
}
