package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /travelPass: zone-locked, time-boxed extra GB granted directly (back-office seam). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelPassRequest(String partyId, String usageType, String zone, BigDecimal amountGB,
        String validFrom, Long validityDays, String validTo) {
}
