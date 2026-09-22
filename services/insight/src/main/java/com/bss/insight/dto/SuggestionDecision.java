package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The human's verdict on a suggestion — and, on accept, the preset saved or the action to run. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "decision", "preset", "action"})
public record SuggestionDecision(String id, String decision, DeskPresetView preset, SuggestedAction action) {
}
