package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * One account's collection case: where it stands on the ladder, what holds
 * it, what was taken. The same record is the API face and the event payload.
 */
@JsonPropertyOrder({"id", "accountId", "state", "overdueBalance", "oldestDueAt", "stepIndex", "feeCount",
        "warnedAt", "holds", "enforcedServices", "curedAt", "writtenOffAt", "writeOffReason", "relatedParty", "@type"})
public record CollectionCaseView(String id, String accountId, String state, Money overdueBalance,
        @JsonInclude(JsonInclude.Include.NON_NULL) String oldestDueAt,
        int stepIndex, int feeCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String warnedAt,
        Holds holds, List<String> enforcedServices,
        @JsonInclude(JsonInclude.Include.NON_NULL) String curedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String writtenOffAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String writeOffReason,
        List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) implements DunningRow {

    /** What pauses the ladder: a promise, a contested amount, hardship. Empty = {}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"promiseToPay", "dispute", "hardship"})
    public record Holds(PromiseHold promiseToPay, DisputeHold dispute, Boolean hardship) {
    }

    @JsonPropertyOrder({"amount", "dueAt"})
    public record PromiseHold(BigDecimal amount, String dueAt) {
    }

    public record DisputeHold(BigDecimal amount) {
    }
}
