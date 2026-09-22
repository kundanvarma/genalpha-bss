package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** Whether an agreement may swap now, and the sentence that says why. */
@JsonPropertyOrder({"agreementId", "paidSharePct", "installmentsPaid", "eligible", "reason"})
public record UpgradeEligibility(String agreementId, BigDecimal paidSharePct, int installmentsPaid,
        boolean eligible, String reason) {
}
