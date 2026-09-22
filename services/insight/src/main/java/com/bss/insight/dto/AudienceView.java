package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * A saved audience. The criteria tree is a marketer-authored document —
 * {@code {all|any:[..]} | {not:{..}} | {type,key?,op?,value}} — so it stays a
 * {@link JsonNode} inside the typed envelope; the service evaluates it, never
 * re-shapes it.
 */
@JsonPropertyOrder({"id", "href", "name", "population", "materializedAt", "memberCount", "criteria", "lastUpdate", "@type"})
public record AudienceView(String id, String href, String name, String population,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime materializedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer memberCount,
        JsonNode criteria, OffsetDateTime lastUpdate, @JsonProperty("@type") String type) {
}
