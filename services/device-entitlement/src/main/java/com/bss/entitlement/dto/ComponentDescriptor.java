package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * How the component describes itself to the Operational Semantic Registry
 * (the ontology's ninth principle). The registry's conformance suite
 * compares this document with its own entry, so the shape is a contract.
 */
@JsonPropertyOrder({"component", "meaning", "manages", "events", "topic", "routes", "@type"})
public record ComponentDescriptor(
        String component,
        String meaning,
        List<String> manages,
        List<String> events,
        String topic,
        List<String> routes,
        @JsonProperty("@type") String type) {
}
