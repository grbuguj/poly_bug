package com.example.poly_bug.repository;

import com.example.poly_bug.entity.ReflectionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReflectionLogRepository extends JpaRepository<ReflectionLog, Long> {
    List<ReflectionLog> findTop5ByOrderByCreatedAtDesc();
}
