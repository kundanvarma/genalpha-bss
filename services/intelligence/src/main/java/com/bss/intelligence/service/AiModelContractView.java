package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * TMF915 aiModelContract, projected: one governed scenario (use case) with
 * its real monitoring numbers, the last human decision on it, the models
 * that served it and the tenant guardrail it runs under.
 */
@JsonPropertyOrder({"id", "href", "name", "state", "lastDecision", "servedBy", "monitoring", "guardrail", "@type"})
public record AiModelContractView(
        String id,
        String href,
        String name,
        String state,
        @JsonInclude(JsonInclude.Include.NON_NULL) LastDecision lastDecision,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ModelRef> servedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) Monitoring monitoring,
        Guardrail guardrail,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"decidedAt", "note"})
    public record LastDecision(OffsetDateTime decidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note) {
    }

    @JsonPropertyOrder({"id", "@referredType"})
    public record ModelRef(String id, @JsonProperty("@referredType") String referredType) {
    }

    /** The ledger's numbers for the scenario; outcome counts per class when any exist. */
    @JsonPropertyOrder({"calls", "promptTokens", "completionTokens", "costMicros", "avgLatencyMs", "outcome"})
    public record Monitoring(long calls, long promptTokens, long completionTokens, long costMicros,
            long avgLatencyMs, @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> outcome) {
    }

    @JsonPropertyOrder({"tenantKillSwitch", "budgetMicros", "windowHours"})
    public record Guardrail(String tenantKillSwitch, long budgetMicros, int windowHours) {
    }
}
