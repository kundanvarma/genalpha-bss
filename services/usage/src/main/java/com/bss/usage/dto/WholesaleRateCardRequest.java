package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /wholesaleRateCard: upsert by usage type. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WholesaleRateCardRequest(String usageSpecName, BigDecimal wholesaleRate, String unit, String currency,
        String hostPartyId, String hostName) {
}
