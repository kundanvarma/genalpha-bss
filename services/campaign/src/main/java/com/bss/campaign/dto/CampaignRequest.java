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
     * The one message a campaign speaks when it has no arms. {@code aiDraft} is
     * the receipt the AI copy assistant issued with the draft: with it the server
     * marks the copy itself and names the model, and {@code aiGenerated: false}
     * cannot clear the mark — editing the draft keeps it, because it is still
     * AI-assisted copy. Without a receipt, {@code aiGenerated}/{@code aiModel}
     * are a declaration (a model name alone marks it too); with neither the copy
     * is human. See {@code AiProvenance}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String subject, String content, Boolean aiGenerated, String aiModel, String aiDraft) {

        @JsonCreator
        public Message {
        }

        public Message(String subject, String content, Boolean aiGenerated, String aiModel) {
            this(subject, content, aiGenerated, aiModel, null);
        }

        public Message(String subject, String content) {
            this(subject, content, null, null, null);
        }
    }
}
