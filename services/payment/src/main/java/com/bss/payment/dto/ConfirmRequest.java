package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The return leg of a redirect session. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmRequest(
        @JsonProperty("provider") String provider,
        @JsonProperty("sessionId") String sessionId) {
}
