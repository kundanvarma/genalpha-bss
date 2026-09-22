package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A pricing rule's public face for the shop window: message and audience,
 * the adjustment it makes, and the other offerings it mentions — never the
 * condition itself.
 */
@JsonPropertyOrder({"name", "message", "audience", "adjustmentType", "adjustmentValue", "relatedOfferingIds"})
public record Teaser(String name, String message, String audience, String adjustmentType, BigDecimal adjustmentValue,
        List<String> relatedOfferingIds) {
}
