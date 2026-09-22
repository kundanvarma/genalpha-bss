package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The granted pass. */
@JsonPropertyOrder({"id", "partyId", "usageType", "zone", "amountGB", "validFor", "@type"})
public record TravelPassView(String id, String partyId, String usageType, String zone, BigDecimal amountGB,
        TimePeriod validFor, @JsonProperty("@type") String type) {
}
