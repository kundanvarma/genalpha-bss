package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * What an agent posts to open or update a session: the items it wants and,
 * optionally, who it is buying for. A configured bundle carries its picks
 * TMF760-shaped under {@code configuration}; {@code buyer} is the agent's
 * own document and is kept as posted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutSessionRequest(List<RequestedItem> items, JsonNode buyer) {

    public static final CheckoutSessionRequest EMPTY = new CheckoutSessionRequest(null, null);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestedItem(String id, Integer quantity, JsonNode configuration) {
    }
}
