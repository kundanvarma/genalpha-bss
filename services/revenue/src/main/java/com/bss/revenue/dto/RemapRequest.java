package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Finance renames an account. {@code configValue} distinguishes absent from an
 * explicit null — absent leaves the setting alone, JSON null clears it — so it
 * rides as a tree, never an Optional.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemapRequest(
        @JsonProperty("key") String key,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("configValue") JsonNode configValue) {
}
