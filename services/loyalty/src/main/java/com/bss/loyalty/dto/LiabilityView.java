package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The number finance books. */
@JsonPropertyOrder({"outstandingPoints", "definition"})
public record LiabilityView(long outstandingPoints, String definition) {

    public static LiabilityView of(long outstandingPoints) {
        return new LiabilityView(outstandingPoints,
                "sum of all member balances — the operator's points liability");
    }
}
