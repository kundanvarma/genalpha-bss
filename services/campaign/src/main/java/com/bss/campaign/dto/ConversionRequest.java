package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** A conversion that did not arrive as an event: who converted and what it is worth a month. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversionRequest(String partyId, BigDecimal value) {
}
