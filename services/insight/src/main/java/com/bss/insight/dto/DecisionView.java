package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of the decision log. The outcome block is unwrapped and absent until
 * an outcome arrives — then all three keys are written, a null value included,
 * because that is what the desks and the ontology's sweeper read today.
 */
@JsonPropertyOrder({"decisionId", "decisionPoint", "subjectType", "subjectId", "candidates", "eligibleActions", "constraints",
        "action", "propensity", "policy", "policyVersion", "reason", "context", "evidence", "autonomy", "fallback", "source",
        "decidedAt", "contract", "outcome", "@type"})
public record DecisionView(String decisionId, String decisionPoint, String subjectType, String subjectId, JsonNode candidates,
        JsonNode eligibleActions, JsonNode constraints, String action, BigDecimal propensity, String policy,
        String policyVersion, String reason, JsonNode context, JsonNode evidence, String autonomy, boolean fallback,
        String source, OffsetDateTime decidedAt, String contract, @JsonUnwrapped Outcome outcome,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"outcome", "outcomeValue", "outcomeAt"})
    public record Outcome(String outcome, BigDecimal outcomeValue, OffsetDateTime outcomeAt) {
    }

    /** The same row re-labelled as a receipt. */
    public DecisionView asReceipt() {
        return new DecisionView(decisionId, decisionPoint, subjectType, subjectId, candidates, eligibleActions, constraints,
                action, propensity, policy, policyVersion, reason, context, evidence, autonomy, fallback, source, decidedAt,
                contract, outcome, "DecisionReceipt");
    }
}
