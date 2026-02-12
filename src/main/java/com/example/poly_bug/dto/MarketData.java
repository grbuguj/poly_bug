package com.example.poly_bug.dto;

import lombok.*;
import java.util.List;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketData {
    private String id;
    private String title;
    private Double yesPrice;
    private Double noPrice;
    private String volume;
    private String endDate;
    private boolean active;
    private List<String> tags;
}
