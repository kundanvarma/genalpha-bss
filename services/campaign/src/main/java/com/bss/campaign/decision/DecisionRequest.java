package com.bss.campaign.decision;

import java.util.List;
import java.util.Map;

/**
 * What a DecisionPoint hands its policy: WHO the choice is about, WHAT may be
 * chosen (already filtered by the constraints), and the PII-free context the
 * policy may look at. Context keys are identifiers and numbers — never names,
 * addresses or message text — because the whole request lands in the log.
 */
public record DecisionRequest(
        String decisionPoint,
        String subjectType,
        String subjectId,
        Map<String, Object> context,
        List<String> eligibleActions) {

    public DecisionRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
        eligibleActions = eligibleActions == null ? List.of() : List.copyOf(eligibleActions);
    }

    public Object ctx(String key) {
        return context.get(key);
    }

    public int ctxInt(String key, int dflt) {
        Object v = context.get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    public double ctxDouble(String key, double dflt) {
        Object v = context.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public String ctxString(String key) {
        Object v = context.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
