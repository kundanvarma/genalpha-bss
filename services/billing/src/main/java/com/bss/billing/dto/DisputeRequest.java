package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Open a dispute: what looks wrong. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisputeRequest(String reason) {
}
