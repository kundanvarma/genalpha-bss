package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Issue a credit note: the reason (required), an amount (default the whole
 * remaining due), or the rate lines to reverse, each with its own amount.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditNoteRequest(BigDecimal amount, String reason, List<Line> lines) {

    public static CreditNoteRequest of(BigDecimal amount, String reason) {
        return new CreditNoteRequest(amount, reason, null);
    }

    /** A line to credit: the rate line's id, optionally a partial amount. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String id, BigDecimal amount) {
    }
}
