package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The wire form of a decision — what the seam publishes as
 * {@code DecisionRecordedEvent} and what a dry run answers with. Keys and
 * order are the ones the decision log (insight) and the ontology's outcome
 * sweep read; {@code context} and {@code evidence} are what the point wrote,
 * passed through as written. {@code decidedAt} is the clock's own text, as it
 * always was.
 */
@JsonPropertyOrder({"decisionId", "decisionPoint", "subjectType", "subjectId", "candidates", "eligibleActions",
        "constraints", "action", "propensity", "policy", "policyVersion", "reason", "context", "evidence", "autonomy",
        "fallback", "source", "contract", "decidedAt", "@type"})
public record DecisionView(
        @JsonInclude(JsonInclude.Include.NON_NULL) String decisionId,
        String decisionPoint,
        String subjectType,
        String subjectId,
        List<String> candidates,
        List<String> eligibleActions,
        List<String> constraints,
        String action,
        Double propensity,
        String policy,
        String policyVersion,
        String reason,
        Map<String, Object> context,
        Map<String, Object> evidence,
        String autonomy,
        boolean fallback,
        String source,
        String contract,
        String decidedAt,
        @JsonProperty("@type") String type) {

    /** The same decision re-labelled for a dry run: nothing was recorded, so no id. */
    public DecisionView asDryRun() {
        return new DecisionView(null, decisionPoint, subjectType, subjectId, candidates, eligibleActions, constraints,
                action, propensity, policy, policyVersion, reason, context, evidence, autonomy, fallback, source,
                contract, decidedAt, "DecisionDryRun");
    }
}
