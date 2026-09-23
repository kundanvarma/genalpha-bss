package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The wave, accounted for: how many subscribers sit in each state, and who
 * the plan has grandfathered out of it. {@code byState} is a keyed
 * collection — the states are the engine's own vocabulary, in its own order.
 */
@JsonPropertyOrder({"planId", "name", "state", "consecutiveFailures", "totalCustomers", "byState",
    "grandfatheredPartyIds"})
public record MigrationProgress(
        String planId,
        String name,
        String state,
        int consecutiveFailures,
        long totalCustomers,
        Map<String, Long> byState,
        List<String> grandfatheredPartyIds) {
}
