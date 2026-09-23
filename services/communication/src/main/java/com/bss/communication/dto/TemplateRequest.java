package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Authoring a template. {@code locales} is the author's document (an object, or
 * an already-encoded JSON string — both accepted as before), and
 * {@code promotionRef} distinguishes absent from an explicit null on a PATCH,
 * so it rides as a tree: absent leaves the row alone, JSON null clears it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateRequest(
        @JsonProperty("name") String name,
        @JsonProperty("channel") String channel,
        @JsonProperty("locales") JsonNode locales,
        @JsonProperty("promotionRef") JsonNode promotionRef) {
}
