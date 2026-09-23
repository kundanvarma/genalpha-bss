package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An SLA in force, projected live from the agreement that carries the terms.
 * Everything but the name is the agreement's own document, answered as the
 * agreement service wrote it — this service reads the terms, it never reshapes
 * them.
 */
@JsonPropertyOrder({"id", "name", "state", "relatedParty", "template", "@type"})
public record SlaView(JsonNode id, String name, JsonNode state,
                      JsonNode relatedParty, JsonNode template) {

    @JsonProperty("@type")
    public String atType() {
        return "SLA";
    }
}
