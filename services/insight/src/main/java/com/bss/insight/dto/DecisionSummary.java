package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/** Per decision point: volume, policy in force, propensity coverage, outcome rate. */
@JsonPropertyOrder({"decisions", "withOutcome", "withPropensity", "points", "@type"})
public record DecisionSummary(long decisions, long withOutcome, long withPropensity, List<Point> points,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"decisionPoint", "policy", "policyVersion", "source", "autonomy", "decisions", "withPropensity",
            "withOutcome", "fallbacks", "outcomeRate", "lastDecidedAt"})
    public record Point(String decisionPoint, String policy, String policyVersion, String source, String autonomy,
            long decisions, long withPropensity, long withOutcome, long fallbacks, double outcomeRate,
            OffsetDateTime lastDecidedAt) {
    }
}
