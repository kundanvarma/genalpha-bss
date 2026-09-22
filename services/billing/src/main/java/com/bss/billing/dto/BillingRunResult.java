package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** POST /billingRun answers with a run receipt, or an honest "busy". */
public sealed interface BillingRunResult permits BillingRunResult.Receipt, BillingRunResult.Busy {

    @JsonPropertyOrder({"billsCreated", "customersSkipped", "accountsFailed", "runId", "billingPeriod"})
    record Receipt(int billsCreated, int customersSkipped, int accountsFailed, String runId,
            TimePeriod billingPeriod) implements BillingRunResult {
    }

    @JsonPropertyOrder({"busy", "note"})
    record Busy(boolean busy, String note) implements BillingRunResult {
    }
}
