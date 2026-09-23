package com.bss.document.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a CMS webhook changed: which tenant's rows, and how many. */
@JsonPropertyOrder({"tenantId", "operation", "assetId", "matched"})
public record WebhookResult(String tenantId, String operation, String assetId, int matched) {
}
