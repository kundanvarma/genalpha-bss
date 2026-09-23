package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** Per-account debit/credit totals in the tie-out. */
@JsonPropertyOrder({"accountCode", "accountName", "debit", "credit"})
public record AccountTotal(
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("debit") BigDecimal debit,
        @JsonProperty("credit") BigDecimal credit) {

    public AccountTotal plus(BigDecimal moreDebit, BigDecimal moreCredit) {
        return new AccountTotal(accountCode, accountName, debit.add(moreDebit), credit.add(moreCredit));
    }
}
