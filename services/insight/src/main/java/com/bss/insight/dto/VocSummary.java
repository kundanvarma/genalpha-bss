package com.bss.insight.dto;

import com.bss.insight.entity.VocAlert;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Voice of Customer v1: battery aggregates per aspect, the alerts fired, and an honest method label. */
@JsonPropertyOrder({"windowDays", "classifiedSignals", "aspects", "alerts", "method"})
public record VocSummary(int windowDays, int classifiedSignals, List<Aspect> aspects, List<VocAlert> alerts, String method) {

    @JsonPropertyOrder({"aspect", "total", "positive", "neutral", "negative", "thisWeek", "weekNegatives", "painPoints",
            "baselineWeeklyNegatives", "deviating"})
    public record Aspect(String aspect, int total, int positive, int neutral, int negative, int thisWeek, int weekNegatives,
            List<PainPoint> painPoints, double baselineWeeklyNegatives, boolean deviating) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"painPoint", "impact", "signalId"})
    public record PainPoint(String painPoint, Integer impact, String signalId) {
    }
}
