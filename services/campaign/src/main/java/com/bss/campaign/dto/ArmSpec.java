package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One A/B arm of a campaign: a complete message with a name. Stored as this JSON, read back as this record. */
@JsonPropertyOrder({"name", "subject", "content"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArmSpec(String name, String subject, String content) {
}
