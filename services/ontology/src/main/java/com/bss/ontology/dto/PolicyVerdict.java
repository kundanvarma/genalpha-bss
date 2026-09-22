package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the policy domain said: {@code none} when the action has no domain,
 * {@code unknown} when the policy service did not answer, else its decision
 * with the rule that made it.
 */
@JsonPropertyOrder({"domain", "decision", "ruleName", "message", "says"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyVerdict(String domain, String decision, String ruleName, String message, String says) {

    public static PolicyVerdict none() {
        return new PolicyVerdict(null, "none", null, null, "no policy domain applies to this action");
    }

    public boolean denied() {
        return "deny".equals(decision);
    }
}
