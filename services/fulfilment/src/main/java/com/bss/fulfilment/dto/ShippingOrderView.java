package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * TMF700 shippingOrder — the parcel as a resource. The items and the delivery
 * place are the order's own documents, stored verbatim in their JSON columns
 * (an unreadable or absent column answered an empty list, and still does);
 * everything the carrier adds is NON_NULL because the map left those keys off
 * until a booking existed.
 */
@JsonPropertyOrder({"id", "href", "productOrderId", "state", "shippingOrderItem", "place",
        "trackingRef", "carrier", "trackingUrl", "deliveryMethod", "pickupPoint",
        "relatedParty", "createdAt", "@type"})
public record ShippingOrderView(
        String id,
        String href,
        String productOrderId,
        String state,
        JsonNode shippingOrderItem,
        JsonNode place,
        @JsonInclude(JsonInclude.Include.NON_NULL) String trackingRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String carrier,
        @JsonInclude(JsonInclude.Include.NON_NULL) String trackingUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deliveryMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL) String pickupPoint,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        OffsetDateTime createdAt) {

    @JsonProperty("@type")
    public String atType() {
        return "ShippingOrder";
    }
}
