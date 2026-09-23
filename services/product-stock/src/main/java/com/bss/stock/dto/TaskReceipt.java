package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a release or consume task did to the shelf. */
@JsonPropertyOrder({"state", "reservations"})
public record TaskReceipt(
        @JsonProperty("state") String state,
        @JsonProperty("reservations") int reservations) {
}
