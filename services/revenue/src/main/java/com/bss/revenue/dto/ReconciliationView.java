package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The tie-out finance runs before trusting either system. */
@JsonPropertyOrder({"date", "entries", "allEntriesBalanced", "billedTotal", "cashTotal",
        "bnplReceivableTotal", "byAccount", "loyaltyPointsLiability", "closedThrough", "@type"})
public record ReconciliationView(
        @JsonProperty("date") String date,
        @JsonProperty("entries") int entries,
        @JsonProperty("allEntriesBalanced") boolean allEntriesBalanced,
        @JsonProperty("billedTotal") BigDecimal billedTotal,
        @JsonProperty("cashTotal") BigDecimal cashTotal,
        @JsonProperty("bnplReceivableTotal") BigDecimal bnplReceivableTotal,
        @JsonProperty("byAccount") List<AccountTotal> byAccount,
        @JsonProperty("loyaltyPointsLiability") LoyaltyControl loyaltyPointsLiability,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("closedThrough") String closedThrough,
        @JsonProperty("@type") String type) {

    /** The close attestation arrives after the totals, in its own slot. */
    public ReconciliationView closedThrough(String through) {
        return new ReconciliationView(date, entries, allEntriesBalanced, billedTotal, cashTotal,
                bnplReceivableTotal, byAccount, loyaltyPointsLiability, through, type);
    }
}
