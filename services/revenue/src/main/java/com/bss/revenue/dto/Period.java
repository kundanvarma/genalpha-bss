package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The window a report covers. */
@JsonPropertyOrder({"fromDate", "toDate"})
public record Period(@JsonProperty("fromDate") String fromDate, @JsonProperty("toDate") String toDate) {
}
