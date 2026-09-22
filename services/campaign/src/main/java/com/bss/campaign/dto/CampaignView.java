package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A campaign as the desk reads it: trigger, message (or arms), promotion code, holdout and conversion window. */
@JsonPropertyOrder({"id", "href", "name", "status", "triggerEventType", "triggerState", "message", "promotionCode",
        "segmentName", "audienceRef", "messageVariants", "holdoutPercent", "conversionWindowDays", "conversionEvent",
        "@type"})
public record CampaignView(
        String id,
        String href,
        String name,
        String status,
        String triggerEventType,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triggerState,
        Message message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String promotionCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String segmentName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String audienceRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ArmSpec> messageVariants,
        int holdoutPercent,
        int conversionWindowDays,
        @JsonInclude(JsonInclude.Include.NON_NULL) String conversionEvent,
        @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"subject", "content"})
    public record Message(String subject, String content) {
    }
}
