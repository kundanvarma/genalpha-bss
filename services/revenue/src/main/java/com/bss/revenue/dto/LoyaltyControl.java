package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The loyalty control number in the tie-out — points, or why there are none. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"points", "note"})
public record LoyaltyControl(@JsonProperty("points") Long points, @JsonProperty("note") String note) {

    public static LoyaltyControl unreachable() {
        return new LoyaltyControl(null, "no loyalty component reachable");
    }

    public static LoyaltyControl of(long points) {
        return new LoyaltyControl(points,
                "control number — no currency valuation configured (see plan P2)");
    }
}
