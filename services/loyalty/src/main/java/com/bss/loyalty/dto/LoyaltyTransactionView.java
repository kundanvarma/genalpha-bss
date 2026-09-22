package com.bss.loyalty.dto;

import com.bss.loyalty.entity.LoyaltyTransaction;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One journal row: every earn and burn keeps its cause. */
@JsonPropertyOrder({"type", "points", "cause", "createdAt"})
public record LoyaltyTransactionView(String type, long points, String cause, OffsetDateTime createdAt) {

    public static LoyaltyTransactionView of(LoyaltyTransaction t) {
        return new LoyaltyTransactionView(t.getTxType(), t.getPoints(), t.getCause(), t.getCreatedAt());
    }
}
