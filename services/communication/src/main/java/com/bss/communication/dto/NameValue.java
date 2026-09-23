package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF characteristic. The value stays open — nobody types those. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "value"})
public record NameValue(
        @JsonProperty("name") String name,
        @JsonProperty("value") Object value) {
}
