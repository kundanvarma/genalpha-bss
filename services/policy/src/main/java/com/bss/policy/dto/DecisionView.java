package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A decision on the wire has two honest shapes: a plain allow (no rule had
 * an opinion) and a decision a named rule made — a deny with its
 * customer-facing message, or an allow by a named permission (a launch
 * envelope) whose message may be null and is written as such.
 */
public sealed interface DecisionView permits DecisionView.Allowed, DecisionView.ByRule {

    record Allowed(String decision) implements DecisionView {

        public static final Allowed INSTANCE = new Allowed("allow");
    }

    @JsonPropertyOrder({"decision", "ruleId", "ruleName", "message"})
    record ByRule(String decision, String ruleId, String ruleName, String message) implements DecisionView {
    }
}
