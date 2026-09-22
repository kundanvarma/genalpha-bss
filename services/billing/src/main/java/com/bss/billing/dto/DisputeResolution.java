package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The decision: outcome credit (with an amount) or uphold, with a note. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisputeResolution(String outcome, String note, BigDecimal amount) {
}
