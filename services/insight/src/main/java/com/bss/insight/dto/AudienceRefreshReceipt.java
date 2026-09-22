package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** The audience was frozen: which path resolved it, how many, when. */
@JsonPropertyOrder({"audienceId", "path", "memberCount", "materializedAt"})
public record AudienceRefreshReceipt(String audienceId, String path, int memberCount, OffsetDateTime materializedAt) {
}
