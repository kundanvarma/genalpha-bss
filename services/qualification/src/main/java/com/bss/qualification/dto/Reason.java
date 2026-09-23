package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Why an item did not qualify, in a code a channel can branch on. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"code", "label"})
public record Reason(String code, String label) {
}
