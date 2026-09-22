package com.bss.intelligence.incident;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The learning curve as data: how many incidents the runbooks now diagnose without a model. */
@JsonPropertyOrder({"traces", "fromLlm", "fromRunbook", "autoDiagnosedRate", "verdicts", "@type"})
public record IncidentStats(
        int traces,
        long fromLlm,
        long fromRunbook,
        double autoDiagnosedRate,
        Verdicts verdicts,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"useful", "notUseful", "pending"})
    public record Verdicts(long useful, long notUseful, long pending) {
    }
}
