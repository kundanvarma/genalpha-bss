package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** An operator's connector binding. Secrets are references only — values are never returned; config is the stored JSON text. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "kind", "source", "mode", "baseUrl", "secretRef", "webhookSecretRef", "config", "enabled",
        "lastSyncAt", "@type"})
public record SignalConnectorView(String id, String name, String kind, String source, String mode, String baseUrl,
        String secretRef, String webhookSecretRef, String config, boolean enabled, OffsetDateTime lastSyncAt,
        @JsonProperty("@type") String type) {
}
