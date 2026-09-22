package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** A saved form preset; values are the form's own field document. */
@JsonPropertyOrder({"id", "desk", "form", "name", "values", "createdAt"})
public record DeskPresetView(String id, String desk, String form, String name, JsonNode values, OffsetDateTime createdAt) {
}
