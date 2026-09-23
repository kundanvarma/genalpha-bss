package com.bss.inventory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * What this component says about itself (the ontology's ninth principle).
 * The registry compares these keys with its own entry, so the shape is a
 * contract and not a debug dump.
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

    public static ComponentDescriptor of(String component, String meaning, List<String> manages,
            List<String> events, String topic, List<String> routes) {
        return new ComponentDescriptor(component, meaning, manages, events, topic, routes,
                "GenAlphaComponent");
    }
}
