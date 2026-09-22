package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The situation summarised: one entry per kind with its severity, whether the
 * customer must act, how many lines it covers and words that fit the count —
 * what a Home or a desk shows instead of one row per event.
 */
@JsonPropertyOrder({"kind", "severity", "actionRequired", "count", "members", "says", "amount", "currency", "dueAt", "name"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SituationSummary(String kind, String severity, boolean actionRequired, int count, List<String> members, String says,
        String amount, String currency, String dueAt, String name) {
}
