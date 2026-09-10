package com.bss.campaign.decision;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the seam writes for every decision — the receipt's raw material. It is
 * published as {@code DecisionRecordedEvent} and the enrolment/execution row
 * keeps only the id, so a later outcome joins back by one key.
 */
public record DecisionRecord(
        String decisionId,
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
        OffsetDateTime decidedAt) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", decisionId);
        m.put("decisionPoint", decisionPoint);
        m.put("subjectType", subjectType);
        m.put("subjectId", subjectId);
        m.put("candidates", candidates);
        m.put("eligibleActions", eligibleActions);
        m.put("constraints", constraints);
        m.put("action", action);
        m.put("propensity", propensity);
        m.put("policy", policy);
        m.put("policyVersion", policyVersion);
        m.put("reason", reason);
        m.put("context", context);
        m.put("evidence", evidence);
        m.put("autonomy", autonomy);
        m.put("fallback", fallback);
        m.put("source", source);
        m.put("decidedAt", decidedAt.toString());
        m.put("@type", "Decision");
        return m;
    }
}
