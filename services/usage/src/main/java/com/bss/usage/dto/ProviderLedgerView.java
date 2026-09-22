package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One row of the host's wholesale AR: an external MVNO's period units of one usage type at its rate. */
@JsonPropertyOrder({"id", "mvnoPartyId", "mvnoName", "periodStart", "usageSpecName", "totalUnits", "unit", "rate",
        "amount", "currency", "@type"})
public record ProviderLedgerView(String id, String mvnoPartyId, String mvnoName, String periodStart,
        String usageSpecName, BigDecimal totalUnits, String unit, BigDecimal rate, BigDecimal amount, String currency,
        @JsonProperty("@type") String type) implements ProviderUsageResult {
}
