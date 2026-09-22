package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Resolve one parked row to the bill it belongs to. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplyUnappliedRequest(String billId) {
}
