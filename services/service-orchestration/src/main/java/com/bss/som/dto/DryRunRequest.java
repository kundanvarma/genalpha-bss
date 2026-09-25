package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * "What will happen when someone orders this?" — the offering to plan for,
 * optional product characteristics the shopper would pick (they win over the
 * product spec, as on a real order item) and an optional post code, so an
 * environment-gated seam (wholesale access) can judge its precondition.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DryRunRequest(String offeringId, Map<String, String> characteristics, String postCode) {
}
