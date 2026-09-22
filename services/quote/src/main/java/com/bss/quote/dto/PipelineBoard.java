package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The board: open deals per stage and per forecast category, and the weighted number a manager commits. */
@JsonPropertyOrder({"stages", "byCategory", "openCount", "openAmount", "weightedForecast", "currency"})
public record PipelineBoard(List<StageColumn> stages, List<CategoryColumn> byCategory, int openCount,
        BigDecimal openAmount, BigDecimal weightedForecast, String currency) {

    @JsonPropertyOrder({"stage", "count", "amount", "weighted"})
    public record StageColumn(String stage, int count, BigDecimal amount, BigDecimal weighted) {
    }

    @JsonPropertyOrder({"category", "count", "amount"})
    public record CategoryColumn(String category, int count, BigDecimal amount) {
    }
}
