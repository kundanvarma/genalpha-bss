package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * TMF915 aiModel, projected: a language model the ledger proves has served
 * (tiers and scenarios observed), or the one versioned trained artifact
 * (the churn classifier, with its training record).
 */
@JsonPropertyOrder({"id", "href", "name", "provider", "category", "state", "tier", "trainingRecord",
        "servedContract", "@type"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiModelView(
        String id,
        String href,
        String name,
        String provider,
        String category,
        String state,
        List<String> tier,
        TrainingRecord trainingRecord,
        List<String> servedContract,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"sampleCount", "positives", "trainedAt"})
    public record TrainingRecord(int sampleCount, int positives, OffsetDateTime trainedAt) {
    }
}
