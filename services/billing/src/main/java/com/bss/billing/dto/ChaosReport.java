package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** A failure mode priced in currency, read-only, assumptions on its face. */
@JsonPropertyOrder({"@type", "scenario", "outageDays", "openBills", "revenueAtRisk", "currency",
        "billsAgedBeyondTermsAtHorizon", "assumptions"})
public record ChaosReport(@JsonProperty("@type") String type, String scenario, int outageDays, int openBills,
        BigDecimal revenueAtRisk,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
        int billsAgedBeyondTermsAtHorizon, List<String> assumptions) {
}
