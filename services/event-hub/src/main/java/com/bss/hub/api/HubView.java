package com.bss.hub.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * TMF688's listener: a callback, an optional filter, and whether it is
 * still listening. The filter is the partner's own authored list — it goes
 * into the column as posted and comes back verbatim, unread, so it stays
 * an open node. A listener registered without a usable filter has no
 * {@code eventTypes} key at all, and that silence is the contract.
 */
@JsonPropertyOrder({"id", "callback", "eventTypes", "active", "createdAt", "@type"})
public record HubView(
        String id,
        String callback,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode eventTypes,
        boolean active,
        OffsetDateTime createdAt,
        @JsonProperty("@type") String type) {

    public static HubView of(String id, String callback, JsonNode eventTypes,
            boolean active, OffsetDateTime createdAt) {
        return new HubView(id, callback, eventTypes, active, createdAt, "Hub");
    }
}
