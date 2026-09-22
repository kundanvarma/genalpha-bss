package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A campaign message drafted from a brief: the marketer edits and saves. */
@JsonPropertyOrder({"subject", "content", "provider", "model"})
public record CampaignCopy(String subject, String content, String provider, String model) {
}
