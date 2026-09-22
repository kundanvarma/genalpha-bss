package com.bss.intelligence.sim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The fleet's lent prior: an unnamed distribution of churn baselines, or
 * a refusal under the k-anonymity floor. */
@JsonPropertyOrder({"@type", "available", "reason", "contributingTenants", "medianChurnBaselinePct",
        "minPct", "maxPct", "assumptions"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ElasticityBenchmark(
        @JsonProperty("@type") String type,
        boolean available,
        String reason,
        Integer contributingTenants,
        Double medianChurnBaselinePct,
        Double minPct,
        Double maxPct,
        List<String> assumptions) {

    static ElasticityBenchmark refused(String reason) {
        return new ElasticityBenchmark("ElasticityBenchmark", false, reason, null, null, null, null, null);
    }
}
