package com.bss.policy.dto;

import com.bss.policy.entity.PolicyRule;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One rule that NAMES an offering, as the offering's own page reads it back:
 * what the rule is called, which way it acts, what it adjusts, whether it is
 * switched on, and where to go to edit it.
 *
 * Unlike {@link Teaser} — the anonymous shop window, enabled pricing rules
 * reduced to marketing copy — this is back-office configuration and carries
 * blocking rules and DISABLED ones too: a rule that is off is exactly what an
 * operator is hunting when nothing is happening, and a rule still pointing at
 * an offering is the warning they need before retiring it.
 *
 * The CONDITION is deliberately absent. This answers "which rules touch this
 * offering", never "on what terms": the terms are read and edited on the rules
 * page, behind the same {@code policy:read} authority this read requires.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "domain", "effect", "enabled", "priority", "message",
        "adjustmentType", "adjustmentValue", "lastUpdate", "@type"})
public record ReferencingRule(String id, String href, String name, String description, String domain, String effect,
        boolean enabled, int priority, String message, String adjustmentType, BigDecimal adjustmentValue,
        OffsetDateTime lastUpdate, @JsonProperty("@type") String type) {

    /** Money is the entity's BigDecimal as stored, never re-scaled. */
    public static ReferencingRule of(PolicyRule rule) {
        return new ReferencingRule(rule.getId(), rule.getHref(), rule.getName(), rule.getDescription(),
                rule.getDomain(), rule.getEffect(), rule.isEnabled(), rule.getPriority(), rule.getMessage(),
                rule.getAdjustmentType(), rule.getAdjustmentValue(), rule.getLastUpdate(), "ReferencingRule");
    }
}
