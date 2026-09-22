package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /mobileWholesaleProviderUsage: the network's mediation feed for one MVNO, period and usage type. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderUsageRequest(String mvnoPartyId, String mvnoName, String usageSpecName, BigDecimal units,
        String unit, String periodStart) {
}
