package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Which pre-arc bill to onboard into the subledger. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackfillRequest(@JsonProperty("billId") String billId) {
}
