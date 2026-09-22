package com.bss.intelligence.risk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** One answered TMF696 assessment: the score, its level, and every named
 * signal with the evidence it was computed from. */
@JsonPropertyOrder({"id", "href", "status", "relatedParty", "riskAssessmentResult", "assessedAt", "@type"})
public record RiskAssessmentView(
        String id,
        String href,
        String status,
        List<PartyRef> relatedParty,
        RiskResult riskAssessmentResult,
        OffsetDateTime assessedAt,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"overallScore", "riskLevel", "signal"})
    public record RiskResult(int overallScore, String riskLevel, List<RiskSignal> signal) {
    }

    /** A named, additive signal; evidence is the raw values it read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "points", "label", "evidence"})
    public record RiskSignal(String name, int points, String label, Map<String, Object> evidence) {
    }
}
