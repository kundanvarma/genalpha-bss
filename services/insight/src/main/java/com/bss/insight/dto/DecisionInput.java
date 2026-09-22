package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decision as another service published it (DecisionRecordedEvent.decision)
 * or as insight's own desk rules make one. The list-and-object blocks
 * (candidates, eligibleActions, constraints, context, evidence) are the
 * publisher's documents: stored verbatim, read back verbatim.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionInput(String decisionId, String decisionPoint, String subjectType, String subjectId,
        JsonNode candidates, JsonNode eligibleActions, JsonNode constraints, String action, Double propensity,
        String policy, String policyVersion, String reason, JsonNode context, JsonNode evidence, String autonomy,
        Boolean fallback, String source, String decidedAt, String contract) {
}
