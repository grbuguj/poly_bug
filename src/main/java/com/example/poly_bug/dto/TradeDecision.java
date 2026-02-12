package com.example.poly_bug.dto;

import com.example.poly_bug.entity.Trade;
import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeDecision {
    private Trade.TradeAction action;
    private Integer confidence;
    private Double amount;
    private String reason;
    private String marketId;
    private String marketTitle;
    private String coin;
    private String timeframe;
    private String learnedFromPast;
}
