package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * InstallmentPlanCreatedEvent / InstallmentPaidEvent payload: the plan's
 * keys, the bill number, the part just paid (paid only), the customer.
 */
@JsonPropertyOrder({"plan", "billNo", "paidAmount", "relatedParty"})
public record InstallmentPlanEvent(@JsonUnwrapped InstallmentPlanView plan, String billNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal paidAmount,
        List<RelatedPartyRef> relatedParty) {
}
