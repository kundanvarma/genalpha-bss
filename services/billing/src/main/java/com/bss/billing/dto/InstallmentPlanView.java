package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * PAY IN PARTS as both surfaces read it. {@code nextAmount} is written as
 * null once the plan is done; {@code nextDueAt} is left off instead.
 */
@JsonPropertyOrder({"billId", "installments", "paidCount", "amountPer", "lastAmount", "nextAmount",
        "currency", "status", "nextDueAt", "@type"})
public record InstallmentPlanView(String billId, int installments, int paidCount, BigDecimal amountPer,
        BigDecimal lastAmount, BigDecimal nextAmount, String currency, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextDueAt,
        @JsonProperty("@type") String type) {
}
