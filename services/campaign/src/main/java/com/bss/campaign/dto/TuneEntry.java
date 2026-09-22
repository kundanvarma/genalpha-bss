package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * One row of a journey's tuning ledger: the numbers the tuner saw, the
 * weights before, the z it computed (only when it judged), why, what it
 * decided, the weights after, and the decision it was recorded as. Stored
 * as JSON on the journey and read back as the same record.
 */
@JsonPropertyOrder({"at", "arms", "before", "z", "threshold", "why", "decision", "after", "decisionId"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TuneEntry(
        String at,
        List<ArmRow> arms,
        Map<String, Integer> before,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double z,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double threshold,
        String why,
        String decision,
        Map<String, Integer> after,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decisionId) {
}
