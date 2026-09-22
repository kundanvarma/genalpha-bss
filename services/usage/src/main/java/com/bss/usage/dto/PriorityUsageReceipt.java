package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** {status: ignored} or {status: rated, amount, unit}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"status", "amount", "unit"})
public record PriorityUsageReceipt(String status, BigDecimal amount, String unit) {

    public static final PriorityUsageReceipt IGNORED = new PriorityUsageReceipt("ignored", null, null);

    public static PriorityUsageReceipt rated(BigDecimal amount, String unit) {
        return new PriorityUsageReceipt("rated", amount, unit);
    }
}
