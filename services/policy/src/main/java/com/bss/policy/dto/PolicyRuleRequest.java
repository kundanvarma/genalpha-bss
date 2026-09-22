package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST policyRule: the rule as the operator authors it. {@code condition} is
 * JSON-logic — a JSON string or the object itself, both accepted;
 * {@code experience} is the personalization payload (object or text);
 * {@code adjustmentValue} is a number or a numeric string, read leniently
 * as the map was ({@code 10} stays {@code 10.0}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRuleRequest(String name, String description, String domain, String effect, Integer priority,
        Boolean enabled, JsonNode condition, String message, String adjustmentType, JsonNode adjustmentValue,
        JsonNode experience) {
}
