package com.bss.campaign.decision;

import java.util.Map;

/**
 * A policy's answer: the action it chose, the probability with which it chose
 * it (null when the policy is deterministic), why in one sentence, and the
 * numbers that produced it. Propensity is what makes offline evaluation
 * possible later: a decision without it can be audited but never replayed.
 */
public record Decision(String action, Double propensity, String reason, Map<String, Object> evidence) {

    public Decision {
        evidence = evidence == null ? Map.of() : evidence;
    }

    public static Decision deterministic(String action, String reason, Map<String, Object> evidence) {
        return new Decision(action, null, reason, evidence);
    }
}
