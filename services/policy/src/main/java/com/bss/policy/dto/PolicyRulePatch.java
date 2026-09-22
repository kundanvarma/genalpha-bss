package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * PATCH policyRule: every field is a tree because absent and null mean
 * different things — absent leaves the field alone, an explicit null clears
 * it (a cleared effect or domain falls back to its default). A key that is
 * not a rule field is ignored, never stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRulePatch(JsonNode name, JsonNode description, JsonNode domain, JsonNode effect,
        JsonNode priority, JsonNode enabled, JsonNode condition, JsonNode message, JsonNode experience,
        JsonNode adjustmentType, JsonNode adjustmentValue) {
}
