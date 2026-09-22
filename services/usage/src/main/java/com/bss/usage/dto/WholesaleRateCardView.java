package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The host's agreed rate for one usage type (seeker side). */
@JsonPropertyOrder({"id", "usageSpecName", "wholesaleRate", "unit", "currency", "hostPartyId", "hostName", "@type"})
public record WholesaleRateCardView(String id, String usageSpecName, BigDecimal wholesaleRate, String unit,
        String currency, String hostPartyId, String hostName, @JsonProperty("@type") String type) {
}
