package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One customer's move inside the waterfall's last month. */
@JsonPropertyOrder({"month", "partyId", "kind", "delta", "mrr"})
public record DrillRow(
        @JsonProperty("month") String month,
        @JsonProperty("partyId") String partyId,
        @JsonProperty("kind") String kind,
        @JsonProperty("delta") BigDecimal delta,
        @JsonProperty("mrr") BigDecimal mrr) {
}
