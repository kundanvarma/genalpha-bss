package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Honest counts: what this service deleted, nothing more. */
@JsonPropertyOrder({"category", "deleted", "retained"})
public record EraseReceipt(
        @JsonProperty("category") String category,
        @JsonProperty("deleted") int deleted,
        @JsonProperty("retained") int retained) {
}
