package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * What the daily points accrual did. One static factory per path — a skipped
 * accrual says why, a booked one says the arithmetic — with NON_NULL per
 * component so each path writes only its own facts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"posted", "note", "points", "delta", "target"})
public record LoyaltyAccrual(
        @JsonProperty("posted") boolean posted,
        @JsonProperty("note") String note,
        @JsonProperty("points") Long points,
        @JsonProperty("delta") BigDecimal delta,
        @JsonProperty("target") BigDecimal target) {

    public static LoyaltyAccrual skipped(String note) {
        return new LoyaltyAccrual(false, note, null, null, null);
    }

    public static LoyaltyAccrual booked(long points, BigDecimal delta, BigDecimal target) {
        return new LoyaltyAccrual(true, null, points, delta, target);
    }
}
