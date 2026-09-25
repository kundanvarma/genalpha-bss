package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What an operator proposes for one posting key. A record, so the body cannot
 * carry a field it does not declare — the state, the tenant and every date on
 * the change come from the token and the store, never from here.
 *
 * <p>{@code configValue} rides as a tree for the same reason {@link RemapRequest}
 * does: absent leaves the setting alone, an explicit JSON null clears it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigChangeRequest(
        @JsonProperty("postingKey") String postingKey,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("configValue") JsonNode configValue,
        @JsonProperty("reason") String reason) {
}
