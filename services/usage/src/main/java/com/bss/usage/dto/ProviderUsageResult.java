package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/**
 * POST /mobileWholesaleProviderUsage answers with the rated ledger row — or,
 * when a re-report moved a period inside the correction window, the row
 * plus the delta the host's AR books (the ProviderWholesaleReratedEvent).
 */
public sealed interface ProviderUsageResult permits ProviderLedgerView, ProviderUsageResult.Rerated {

    @JsonPropertyOrder({"ledger", "previousAmount", "delta", "rerateCount"})
    record Rerated(@JsonUnwrapped ProviderLedgerView ledger, BigDecimal previousAmount, BigDecimal delta,
            int rerateCount) implements ProviderUsageResult {
    }
}
