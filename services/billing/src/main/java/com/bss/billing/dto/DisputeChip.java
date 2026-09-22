package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The latest dispute on a bill, as the bill carries it. */
@JsonPropertyOrder({"id", "status", "reason"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisputeChip(String id, String status, String reason) {
}
