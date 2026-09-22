package com.bss.policy.dto;

import com.bss.policy.entity.PolicyRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A policy rule as the console, the channels and the rule events read it.
 * {@code condition} is the operator's JSON-logic as authored — text on the
 * wire, never re-shaped here; {@code experience} is the operator's
 * personalization payload, a document, present only when the rule has one.
 * Money is the entity's BigDecimal as stored, never re-scaled.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "domain", "effect", "priority", "enabled", "condition",
        "message", "adjustmentType", "adjustmentValue", "experience", "lastUpdate", "@type"})
public record PolicyRuleView(String id, String href, String name, String description, String domain, String effect,
        int priority, boolean enabled, String condition, String message, String adjustmentType,
        BigDecimal adjustmentValue, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode experience,
        OffsetDateTime lastUpdate, @JsonProperty("@type") String type) {

    public static PolicyRuleView of(PolicyRule rule, JsonNode experience) {
        return new PolicyRuleView(rule.getId(), rule.getHref(), rule.getName(), rule.getDescription(),
                rule.getDomain(), rule.getEffect(), rule.getPriority(), rule.isEnabled(), rule.getCondition(),
                rule.getMessage(), rule.getAdjustmentType(), rule.getAdjustmentValue(), experience,
                rule.getLastUpdate(), "PolicyRule");
    }
}
