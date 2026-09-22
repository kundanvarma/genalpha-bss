package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** Won deals by the programme that sourced the lead — the honest B2B attribution number. */
@JsonPropertyOrder({"wonCount", "wonAmount", "bySource", "currency"})
public record WonReport(int wonCount, BigDecimal wonAmount, List<SourceRow> bySource, String currency) {

    @JsonPropertyOrder({"source", "wonCount", "wonAmount"})
    public record SourceRow(String source, int wonCount, BigDecimal wonAmount) {
    }
}
