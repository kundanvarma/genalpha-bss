package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The guardrails as the desk saves them — full-replace: a save without quiet hours clears them. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MartechSettingsRequest(
        Integer maxMarketingMessages,
        Integer perDays,
        String quietStart,
        String quietEnd,
        String timeZone) {
}
