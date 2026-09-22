package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /providerRateCard: upsert by (MVNO, usage type); no MVNO = the default card. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderRateCardRequest(String mvnoPartyId, String mvnoName, String usageSpecName, BigDecimal rate,
        String unit, String currency) {
}
