package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** An overdue or broken installment plan: who, what is still owed, the clock. */
@JsonPropertyOrder({"billId", "billNo", "partyId", "installments", "paidCount", "remaining", "currency", "status",
        "nextDueAt", "remindedAt", "graceDays", "relatedParty", "@type"})
public record DunningCaseView(String billId, String billNo, String partyId, int installments, int paidCount,
        BigDecimal remaining, String currency, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextDueAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String remindedAt,
        long graceDays, List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) implements DunningRow {
}
