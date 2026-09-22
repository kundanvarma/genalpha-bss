package com.bss.campaign.decision;

import com.bss.campaign.dto.DecisionView;

import java.time.OffsetDateTime;
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
        OffsetDateTime decidedAt,
        String contract) {

    /** The wire form: what is published and what a dry run answers with. */
    public DecisionView view() {
        return new DecisionView(decisionId, decisionPoint, subjectType, subjectId, candidates, eligibleActions,
                constraints, action, propensity, policy, policyVersion, reason, context, evidence, autonomy, fallback,
                source, contract, decidedAt.toString(), "Decision");
    }
}
