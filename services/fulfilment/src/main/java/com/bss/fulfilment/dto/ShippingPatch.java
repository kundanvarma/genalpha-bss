package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the warehouse may change on a parcel. The map read the state with
 * {@code String.valueOf}, so an absent key and an explicit null both asked for
 * the state {@code "null"} and got the same 400 — that refusal is the contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShippingPatch(JsonNode state, JsonNode trackingRef) {

    public static final ShippingPatch EMPTY = new ShippingPatch(null, null);

    public String stateValue() {
        return Json.valueOf(state);
    }

    public String trackingRefValue() {
        return Json.textOrNull(trackingRef);
    }
}
