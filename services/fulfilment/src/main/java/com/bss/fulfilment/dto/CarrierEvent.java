package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The carrier's delivery callback. Unauthenticated, so this is the one body in
 * the service a stranger writes: a record cannot carry a field fulfilment never
 * declared, and the three it does declare keep the map's own reading — a missing
 * tenant falls back to genalpha, a missing parcel id becomes the literal "null"
 * and finds nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CarrierEvent(JsonNode tenantId, JsonNode shippingOrderId, JsonNode status) {

    public static final CarrierEvent EMPTY = new CarrierEvent(null, null, null);

    public String tenant(String fallback) {
        return Json.textOrNull(tenantId) == null ? fallback : Json.valueOf(tenantId);
    }

    public String parcelId() {
        return Json.valueOf(shippingOrderId);
    }

    public String statusValue() {
        return Json.valueOf(status);
    }
}
