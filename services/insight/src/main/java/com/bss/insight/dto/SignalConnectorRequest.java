package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Bind (or rebind) a connector by name. Config is the adapter's own document (JSON pointers for a webhook, say). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalConnectorRequest(String name, String kind, String source, String mode, String baseUrl, String secretRef,
        String webhookSecretRef, JsonNode config, Boolean enabled) {
}
