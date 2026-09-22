package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The rate the host charges one MVNO (or every MVNO, when {@code mvnoPartyId} is null) for a usage type. */
@JsonPropertyOrder({"id", "mvnoPartyId", "mvnoName", "usageSpecName", "rate", "unit", "currency", "@type"})
public record ProviderRateCardView(String id, String mvnoPartyId, String mvnoName, String usageSpecName,
        BigDecimal rate, String unit, String currency, @JsonProperty("@type") String type) {
}
