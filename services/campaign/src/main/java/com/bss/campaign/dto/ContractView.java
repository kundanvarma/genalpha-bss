package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A point's effective contract on the wire: the row the tenant wrote
 * ({@link Stored}) or the defaults, marked as such ({@link Defaults}). Two
 * honest answers with two shapes — a sealed pair, not one record with
 * half its keys null.
 */
public sealed interface ContractView permits ContractView.Stored, ContractView.Defaults {

    @JsonPropertyOrder({"id", "version", "ref", "objective", "secondaryMetrics", "guardrails", "allowedActions",
            "explorationMaxPercent", "autonomy", "fallbackAction", "enabled", "notes", "updatedBy", "lastUpdate",
            "defaults"})
    record Stored(
            String id,
            int version,
            String ref,
            String objective,
            List<String> secondaryMetrics,
            List<String> guardrails,
            List<String> allowedActions,
            Integer explorationMaxPercent,
            String autonomy,
            String fallbackAction,
            boolean enabled,
            String notes,
            String updatedBy,
            OffsetDateTime lastUpdate,
            boolean defaults) implements ContractView {
    }

    @JsonPropertyOrder({"objective", "secondaryMetrics", "guardrails", "allowedActions", "explorationMaxPercent",
            "autonomy", "fallbackAction", "enabled", "version", "defaults"})
    record Defaults(
            String objective,
            List<String> secondaryMetrics,
            List<String> guardrails,
            List<String> allowedActions,
            Integer explorationMaxPercent,
            String autonomy,
            String fallbackAction,
            boolean enabled,
            int version,
            boolean defaults) implements ContractView {

        public static Defaults withObjective(String objective) {
            return new Defaults(objective, List.of(), List.of(), null, null, null, null, true, 0, true);
        }
    }
}
