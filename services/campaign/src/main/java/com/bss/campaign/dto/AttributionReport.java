package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Portfolio attribution: every campaign and journey with its own
 * holdout-vs-treated measurement, the blended portfolio, and the totals per
 * channel. Incremental revenue is summed only over programs that HAVE a
 * control group; a program without one reports {@code incremental: null}.
 */
@JsonPropertyOrder({"programs", "portfolio", "byChannel"})
public record AttributionReport(List<Program> programs, Portfolio portfolio, Map<String, ChannelTotals> byChannel) {

    @JsonPropertyOrder({"type", "id", "name", "status", "conversionEvent", "reached", "heldOut", "conversions",
            "treatedRate", "holdoutRate", "liftPoints", "revenue"})
    public record Program(
            String type,
            String id,
            String name,
            String status,
            String conversionEvent,
            long reached,
            long heldOut,
            Conversions conversions,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double treatedRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double holdoutRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double liftPoints,
            @JsonInclude(JsonInclude.Include.NON_NULL) ProgramRevenue revenue) {
    }

    /** Per side, plus the incremental money — null when there is no control group to measure it against. */
    @JsonPropertyOrder({"treated", "holdout", "incremental"})
    public record ProgramRevenue(BigDecimal treated, BigDecimal holdout, BigDecimal incremental) {
    }

    @JsonPropertyOrder({"programs", "totalReached", "totalHeldOut", "conversions", "blendedTreatedRate",
            "blendedHoldoutRate", "blendedLiftPoints", "revenue", "note"})
    public record Portfolio(
            int programs,
            long totalReached,
            long totalHeldOut,
            Conversions conversions,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double blendedTreatedRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double blendedHoldoutRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double blendedLiftPoints,
            PortfolioRevenue revenue,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note) {
    }

    @JsonPropertyOrder({"grossAttributed", "holdout", "incremental", "basis"})
    public record PortfolioRevenue(BigDecimal grossAttributed, BigDecimal holdout, BigDecimal incremental, String basis) {
    }

    @JsonPropertyOrder({"programs", "reached", "attributedRevenue"})
    public record ChannelTotals(int programs, long reached, BigDecimal attributedRevenue) {
    }
}
