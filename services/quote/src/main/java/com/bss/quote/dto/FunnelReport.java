package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Funnel analytics: stage-to-stage conversion, time in stage, win rate, cycle time, and a sentence a copilot can read. */
@JsonPropertyOrder({"stageConversion", "timeInStage", "winRatePct", "wonCount", "lostCount", "avgCycleDays", "summary"})
public record FunnelReport(List<StageConversion> stageConversion, List<TimeInStage> timeInStage, double winRatePct,
        int wonCount, int lostCount, double avgCycleDays, String summary) {

    @JsonPropertyOrder({"from", "to", "reachedFrom", "reachedTo", "conversionPct"})
    public record StageConversion(String from, String to, int reachedFrom, int reachedTo, double conversionPct) {
    }

    @JsonPropertyOrder({"stage", "avgDays"})
    public record TimeInStage(String stage, double avgDays) {
    }
}
