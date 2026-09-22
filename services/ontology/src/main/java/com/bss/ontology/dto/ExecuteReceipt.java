package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What execute answered: refused (with the condition named), filed for a human
 * approver, refused by the component, or done — with the check that preceded
 * it, who executed it, the component's result and the decision id of the
 * receipt. {@code effects} and {@code emits} are the action's own declarations,
 * copied from the registry.
 */
@JsonPropertyOrder({"action", "check", "executedAs", "executedBy", "done", "filed", "result", "decisionId", "approvalId",
        "refusal", "componentStatus", "effects", "emits", "said"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecuteReceipt(String action, Check check, String executedAs, ExecutedBy executedBy, boolean done, Boolean filed,
        JsonNode result, String decisionId, String approvalId, String refusal, Integer componentStatus, JsonNode effects,
        JsonNode emits, String said) {

    /** The capability that ran the action, on which component, through which route. */
    @JsonPropertyOrder({"component", "capability", "route"})
    public record ExecutedBy(String component, String capability, String route) {
    }
}
