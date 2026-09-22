package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Declaration against reality for one component: what the registry declares,
 * what the running component says about itself, and where they disagree.
 * Short answers (no entry, no self-description) carry only what was learned.
 */
@JsonPropertyOrder({"component", "declared", "runtime", "ok", "missingEvents", "missingRoutes", "servedRoutes", "missingManages", "says"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConformanceResult(String component, Declared declared, Runtime runtime, boolean ok, List<String> missingEvents,
        List<String> missingRoutes, List<String> servedRoutes, List<String> missingManages, String says) {

    /** The registry's entry, verbatim: these are the registry's own shapes. */
    @JsonPropertyOrder({"events", "manages", "capabilities"})
    public record Declared(JsonNode events, JsonNode manages, JsonNode capabilities) {
    }

    /** What the component answered at /.well-known/genalpha-component.json, and how many routes it serves. */
    @JsonPropertyOrder({"events", "manages", "routes"})
    public record Runtime(JsonNode events, JsonNode manages, int routes) {
    }

    public static ConformanceResult unknown(String component, Declared declared, String says) {
        return new ConformanceResult(component, declared, null, false, null, null, null, null, says);
    }
}
