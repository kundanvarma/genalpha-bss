package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /spendMeter/charge: one rated charge lands on a party's meters — the tenant is the caller's, never the body's. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpendChargeRequest(String partyId, String chargeClass, Money amount) {
}
