package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The component's self-description at {@code /.well-known/genalpha-component.json}. */
@JsonPropertyOrder({"component", "meaning", "manages", "events", "topic", "routes", "@type"})
public record ComponentDescriptor(String component, String meaning, List<String> manages, List<String> events,
        String topic, List<String> routes, @JsonProperty("@type") String type) {
}
