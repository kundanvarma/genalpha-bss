package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** What the desk sends to create a campaign: a trigger (event, segment or audience), a message or 2–4 arms, a code. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignRequest(
        String name,
        String status,
        String triggerEventType,
        String triggerState,
        String segmentName,
        String audienceRef,
        Message message,
        List<ArmSpec> messageVariants,
        String promotionCode,
        String conversionEvent,
        Integer conversionWindowDays,
        Integer holdoutPercent) {

    /**
     * The one message a campaign speaks when it has no arms. {@code aiGenerated}
     * and {@code aiModel} say the copy came from the AI copy assistant (the
     * console passes them along with the text it drafted); a model name alone
     * marks it too. Editing the draft keeps the mark — it is AI-assisted copy.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String subject, String content, Boolean aiGenerated, String aiModel) {

        @JsonCreator
        public Message {
        }

        public Message(String subject, String content) {
            this(subject, content, null, null);
        }

        /** True when the copy is declared AI-written, by the flag or by naming a model. */
        public boolean aiWritten() {
            return Boolean.TRUE.equals(aiGenerated) || (aiModel != null && !aiModel.isBlank());
        }
    }
}
