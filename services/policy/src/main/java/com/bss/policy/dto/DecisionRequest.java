package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A question for the rules: which decision point ({@code domain}, default
 * {@code order}) and the request context the JSON-logic is evaluated
 * against. The context is the caller's open document — whatever variables
 * the operator's rules name; anything that is not an object counts as empty.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionRequest(String domain, JsonNode context) {
}
