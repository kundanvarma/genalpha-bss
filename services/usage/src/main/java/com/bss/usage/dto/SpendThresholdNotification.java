package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /internal/ocs/spendThreshold: an external charging edge reports a spend accrual. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpendThresholdNotification(String tenantId, String partyId, String chargeClass, Money amount) {
}
