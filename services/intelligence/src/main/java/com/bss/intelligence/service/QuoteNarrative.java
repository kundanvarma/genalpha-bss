package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The executive summary of a B2B quote, in plain words. */
@JsonPropertyOrder({"narrative", "provider", "model"})
public record QuoteNarrative(String narrative, String provider, String model) {
}
