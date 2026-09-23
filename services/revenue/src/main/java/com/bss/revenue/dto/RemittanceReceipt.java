package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Whether this payout file booked, or was a replay. */
@JsonPropertyOrder({"sourceRef", "posted"})
public record RemittanceReceipt(
        @JsonProperty("sourceRef") String sourceRef,
        @JsonProperty("posted") boolean posted) {
}
