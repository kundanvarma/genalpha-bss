package com.bss.porting.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted port. The party list stays a tree because only the first entry's
 * id is read, and a customer's own token overrides it anyway — the body has
 * never been able to name whose number this is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortingOrderRequest(
        String direction,
        String phoneNumber,
        String country,
        String otherOperator,
        String productOrderId,
        String requestedCutover,
        JsonNode relatedParty) {

    /** The id of the first party named, or null — exactly what the map read. */
    public String firstPartyId() {
        if (relatedParty == null || !relatedParty.isArray() || relatedParty.isEmpty()) {
            return null;
        }
        JsonNode first = relatedParty.get(0);
        JsonNode id = first.isObject() ? first.get("id") : null;
        return id == null || id.isNull() ? null : id.asText();
    }
}
