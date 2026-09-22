package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A trait key/value this tenant actually holds — a real choice for the builder. */
@JsonPropertyOrder({"key", "value"})
public record Facet(String key, String value) {
}
