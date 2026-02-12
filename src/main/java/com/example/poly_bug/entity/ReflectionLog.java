package com.example.poly_bug.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reflection_logs")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReflectionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2000)
    private String content;

    private Double winRate;
    private Integer totalTrades;
    private Double totalProfitLoss;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
