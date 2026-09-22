package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** What the point WOULD decide under the current contract — the decision's keys, then {@code dryRun: true}. */
@JsonPropertyOrder({"decision", "dryRun"})
public record DecisionDryRun(@JsonUnwrapped DecisionView decision, boolean dryRun) {

    public static DecisionDryRun of(DecisionView decision) {
        return new DecisionDryRun(decision.asDryRun(), true);
    }
}
