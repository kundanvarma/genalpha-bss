package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The registry's own self-description at /.well-known/genalpha-component.json. */
@JsonPropertyOrder({"component", "meaning", "manages", "capabilities", "events", "topic", "routes", "invoke", "@type"})
public record ComponentDescriptor(String component, String meaning, List<String> manages, List<String> capabilities, List<String> events,
        String topic, List<String> routes, Invoke invoke, @JsonProperty("@type") String type) {

    /** How to reach the registry: read it, speak MCP, check or execute an action. */
    @JsonPropertyOrder({"read", "mcp", "check", "execute"})
    public record Invoke(String read, String mcp, String check, String execute) {
    }
}
