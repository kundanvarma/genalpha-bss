package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A journey as the desk reads it. {@code steps} and {@code arms} are the
 * author's documents, returned as stored; the arm block (arms, autoTune,
 * armWeights, tuningLog) is present only when the journey has arms.
 */
@JsonPropertyOrder({"id", "href", "name", "status", "triggerEventType", "triggerState", "segmentName",
        "conversionEvent", "holdoutPercent", "category", "priority", "arms", "autoTune", "armWeights", "tuningLog",
        "steps", "stepsEditedAt", "lastUpdate", "@type"})
public record JourneyView(
        String id,
        String href,
        String name,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triggerEventType,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triggerState,
        @JsonInclude(JsonInclude.Include.NON_NULL) String segmentName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String conversionEvent,
        int holdoutPercent,
        String category,
        int priority,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode arms,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean autoTune,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Integer> armWeights,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<TuneEntry> tuningLog,
        JsonNode steps,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime stepsEditedAt,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {
}
