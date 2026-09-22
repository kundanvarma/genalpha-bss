package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/**
 * The DunningStepReachedEvent payload: the case's keys, then the rung it
 * just reached and the oldest overdue bill. Same wire as before, typed.
 */
@JsonPropertyOrder({"collectionCase", "step", "billNo"})
public record DunningStepReached(@JsonUnwrapped CollectionCaseView collectionCase, Step step, String billNo) {

    /** The rung as it was walked: fee actually charged, enforceable date named on a warning. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "offsetDays", "templateId", "feeCharged", "enforceableAt"})
    public record Step(String action, int offsetDays, String templateId, BigDecimal feeCharged,
            String enforceableAt) {
    }
}
