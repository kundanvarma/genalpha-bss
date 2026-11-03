package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A staged journey proposal, in the exact shape the campaign engine accepts. */
@JsonPropertyOrder({"name", "triggerEventType", "holdoutPercent", "steps", "provider", "model", "note"})
public record JourneyDraft(String name, String triggerEventType, int holdoutPercent,
        List<JourneyStep> steps, String provider, String model, String note) {

    /**
     * A message step carries subject and content — and, because a model wrote
     * them, the AI mark ({@code aiGenerated}, {@code aiModel}) and the signed
     * receipt ({@code aiDraft}, see {@link AiDraftProvenance}); a wait step
     * carries days and no mark.
     */
    @JsonPropertyOrder({"type", "stage", "subject", "content", "days", "aiGenerated", "aiModel", "aiDraft"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JourneyStep(String type, String stage, String subject, String content, Integer days,
            Boolean aiGenerated, String aiModel, String aiDraft) {

        public static JourneyStep message(String stage, String subject, String content, String aiModel, String aiDraft) {
            return new JourneyStep("message", stage, subject, content, null, Boolean.TRUE, aiModel, aiDraft);
        }

        public static JourneyStep wait(String stage, int days) {
            return new JourneyStep("wait", stage, null, null, days, null, null, null);
        }
    }
}
