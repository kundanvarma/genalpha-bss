package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Tokenize an approved session and vault the provider's recurring token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VaultRecurringRequest(
        @JsonProperty("provider") String provider,
        @JsonProperty("sessionId") String sessionId) {
}
