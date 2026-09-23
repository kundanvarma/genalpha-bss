package com.bss.hub.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A partner registering a listener. Tenant, id, active and the clock come
 * from the token and the store — a record cannot carry them, so a body
 * that names them is ignored by construction.
 *
 * <p>The two components stay open nodes because the map this replaces read
 * the callback through {@code String.valueOf} (a posted number became its
 * text and still failed the {@code http} test) and accepted the filter
 * only when it really was a non-empty list.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubRequest(JsonNode callback, JsonNode eventTypes) {

    @JsonIgnore
    public String callbackText() {
        return Json.textOrNull(callback);
    }

    /** The filter, only when it is a non-empty list — as before. */
    @JsonIgnore
    public JsonNode eventTypesOrNull() {
        return eventTypes != null && eventTypes.isArray() && !eventTypes.isEmpty()
                ? eventTypes : null;
    }
}
