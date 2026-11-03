package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A campaign message drafted from a brief: the marketer edits and saves.
 * {@code provenance} is the signed receipt ({@link AiDraftProvenance}) the
 * console sends back as {@code message.aiDraft}, so the campaign component
 * marks the copy itself; absent when the operator set no provenance key.
 */
@JsonPropertyOrder({"subject", "content", "provider", "model", "provenance"})
public record CampaignCopy(String subject, String content, String provider, String model,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provenance) {
}
