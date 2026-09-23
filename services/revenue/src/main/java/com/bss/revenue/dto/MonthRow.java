package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One month of the MRR waterfall. The two rates are null when there is no
 * prior month to divide by — written as null, as the map always did. */
@JsonPropertyOrder({"month", "mrr", "newMrr", "expansionMrr", "contractionMrr", "churnedMrr",
        "activeAccounts", "arpu", "churnRatePct", "nrrPct", "baseline"})
public record MonthRow(
        @JsonProperty("month") String month,
        @JsonProperty("mrr") BigDecimal mrr,
        @JsonProperty("newMrr") BigDecimal newMrr,
        @JsonProperty("expansionMrr") BigDecimal expansionMrr,
        @JsonProperty("contractionMrr") BigDecimal contractionMrr,
        @JsonProperty("churnedMrr") BigDecimal churnedMrr,
        @JsonProperty("activeAccounts") int activeAccounts,
        @JsonProperty("arpu") BigDecimal arpu,
        @JsonProperty("churnRatePct") BigDecimal churnRatePct,
        @JsonProperty("nrrPct") BigDecimal nrrPct,
        @JsonProperty("baseline") boolean baseline) {
}
