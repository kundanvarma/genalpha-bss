package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The operator's edit to one PSP binding. {@code methods} and {@code currencies}
 * are stored as the JSON the console posted — a list or an already-encoded string,
 * both accepted as before — so they ride as trees, not as a shape we invent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PspConfigRequest(
        @JsonProperty("provider") String provider,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("secretRef") String secretRef,
        @JsonProperty("webhookSecretRef") String webhookSecretRef,
        @JsonProperty("methods") JsonNode methods,
        @JsonProperty("isDefault") Boolean isDefault,
        @JsonProperty("priority") JsonNode priority,
        @JsonProperty("currencies") JsonNode currencies,
        @JsonProperty("enabled") Boolean enabled) {
}
