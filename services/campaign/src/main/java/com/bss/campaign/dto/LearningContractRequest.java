package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The intent an operator writes for one point. The list fields accept what
 * the desk sends — a JSON list or a comma/newline-separated string — so they
 * ride as {@code JsonNode}; the cap is a {@code JsonNode} too, so a value that
 * is not a whole number is refused with the desk's own message, not a parse error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningContractRequest(
        String objective,
        JsonNode secondaryMetrics,
        JsonNode guardrails,
        JsonNode allowedActions,
        JsonNode explorationMaxPercent,
        String autonomy,
        String fallbackAction,
        JsonNode enabled,
        String notes) {
}
