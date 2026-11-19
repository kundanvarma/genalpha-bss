package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * One row of a journey's tuning ledger: the numbers the tuner saw, the
 * weights before, the evidence (only when it judged), why, what it decided,
 * the weights after, and the decision it was recorded as. {@code odds} against
 * {@code bar} is the test; {@code z} is descriptive. {@code threshold} is the
 * fixed z bar of rows written before the always-valid rule, kept so they read
 * back unchanged. Stored as JSON on the journey and read back as the same record.
 */
@JsonPropertyOrder({"at", "arms", "before", "z", "threshold", "odds", "bar", "why", "decision", "after", "decisionId"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TuneEntry(
        String at,
        List<ArmRow> arms,
        Map<String, Integer> before,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double z,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double threshold,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double odds,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double bar,
        String why,
        String decision,
        Map<String, Integer> after,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decisionId) {
}
