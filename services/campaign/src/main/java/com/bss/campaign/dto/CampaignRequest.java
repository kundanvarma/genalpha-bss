package com.bss.campaign.dto;

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

    /** The one message a campaign speaks when it has no arms. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String subject, String content) {
    }
}
