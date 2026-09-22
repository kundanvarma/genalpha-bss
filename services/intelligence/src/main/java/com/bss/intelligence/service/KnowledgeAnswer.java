package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A grounded answer with its sources. {@code gap} appears only when the
 * question is recorded for the content team; {@code cached}, {@code provider}
 * and {@code model} only when a model (or its cache) answered.
 */
@JsonPropertyOrder({"gap", "answer", "sources", "provider", "model", "cached"})
public record KnowledgeAnswer(
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean gap,
        String answer,
        List<KnowledgeSource> sources,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String model,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean cached) {

    public KnowledgeAnswer cached(boolean value) {
        return new KnowledgeAnswer(gap, answer, sources, provider, model, value);
    }

    @JsonPropertyOrder({"id", "title"})
    public record KnowledgeSource(String id, String title) {
    }
}
