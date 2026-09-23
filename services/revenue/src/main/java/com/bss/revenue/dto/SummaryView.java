package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A governed sales/finance summary, computed once from the subledger.
 * {@code revenueDeltaPct} is written even when null — there may be no prior
 * period to compare against, and the map always carried the key.
 */
@JsonPropertyOrder({"@type", "period", "netRevenue", "taxCollected", "cashCollected",
        "invoicesIssued", "priorNetRevenue", "revenueDeltaPct", "byAccount"})
public record SummaryView(
        @JsonProperty("@type") String type,
        @JsonProperty("period") Period period,
        @JsonProperty("netRevenue") BigDecimal netRevenue,
        @JsonProperty("taxCollected") BigDecimal taxCollected,
        @JsonProperty("cashCollected") BigDecimal cashCollected,
        @JsonProperty("invoicesIssued") long invoicesIssued,
        @JsonProperty("priorNetRevenue") BigDecimal priorNetRevenue,
        @JsonProperty("revenueDeltaPct") BigDecimal revenueDeltaPct,
        @JsonProperty("byAccount") List<AccountNet> byAccount) {
}
