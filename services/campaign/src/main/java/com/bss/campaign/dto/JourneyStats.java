package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The funnel: who entered, who was held out, where the active ones stand
 * (by step and by named stage), how many reached each node, conversions per
 * side, the arm readout when there are arms, the lift, the attributed
 * revenue, and the honesty markers (a tiny holdout, an edit after launch).
 */
@JsonPropertyOrder({"journeyId", "entered", "treated", "heldOut", "activeAtStep", "stageFunnel", "funnel",
        "completedUnconverted", "conversions", "arms", "autoTune", "tuningLog", "treatedRate", "holdoutRate",
        "liftPoints", "note", "revenue", "stepsEditedAt", "editNote"})
public record JourneyStats(
        String journeyId,
        long entered,
        long treated,
        long heldOut,
        Map<String, Long> activeAtStep,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> stageFunnel,
        List<FunnelNode> funnel,
        long completedUnconverted,
        Conversions conversions,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ArmRow> arms,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean autoTune,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<TuneEntry> tuningLog,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double treatedRate,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double holdoutRate,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double liftPoints,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) Revenue revenue,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime stepsEditedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String editNote) {

    /** One node of the per-node funnel: how many reached it, how many are active there now. */
    @JsonPropertyOrder({"index", "type", "stage", "reached", "active"})
    public record FunnelNode(int index, String type,
            @JsonInclude(JsonInclude.Include.NON_NULL) String stage, long reached, long active) {
    }

    /** Attributed revenue per side, and the lift per exposed customer when both sides exist. */
    @JsonPropertyOrder({"treated", "holdout", "liftPerCustomer", "basis"})
    public record Revenue(BigDecimal treated, BigDecimal holdout,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal liftPerCustomer, String basis) {
    }
}
