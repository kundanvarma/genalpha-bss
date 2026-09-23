package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A TMF service characteristic. The value stays open ({@code Object}) — the
 * standard does not type it and neither do we; the name is ours.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "value"})
public record Characteristic(String name, Object value) {
}
