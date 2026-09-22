package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One run's ledger row — the operator's view, newest first. */
@JsonPropertyOrder({"id", "status", "startedAt", "finishedAt", "accountsTotal", "billsCreated", "customersSkipped",
        "accountsFailed", "lastError"})
public record BillingRunView(String id, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
        Integer accountsTotal, Integer billsCreated, Integer customersSkipped, Integer accountsFailed,
        String lastError) {
}
