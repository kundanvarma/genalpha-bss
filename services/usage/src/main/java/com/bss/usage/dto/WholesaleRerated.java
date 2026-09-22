package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/** The WholesaleUsageReratedEvent: the moved row plus the delta the revenue subledger books. */
@JsonPropertyOrder({"ledger", "previousAmount", "delta"})
public record WholesaleRerated(@JsonUnwrapped WholesaleLedgerView ledger, BigDecimal previousAmount,
        BigDecimal delta) {
}
