package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The fiction that would leave for a frontier model, beside the redacted text it stands in for. */
@JsonPropertyOrder({"signalId", "text", "twin", "linkable", "@type"})
public record SignalTwin(String signalId, String text, String twin, boolean linkable, @JsonProperty("@type") String type) {
}
