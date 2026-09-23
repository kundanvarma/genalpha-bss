package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One row of the shopper's delivery menu. {@code carrier} is written even when
 * null (the built-in home fallback names no carrier); the points and the ETA are
 * left off exactly where the map left them off.
 */
@JsonPropertyOrder({"method", "carrier", "carrierName", "points", "eta"})
public record DeliveryOption(
        String method,
        String carrier,
        String carrierName,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<JsonNode> points,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eta) {

    public static final DeliveryOption HOME = new DeliveryOption("home", null, "Helthjem", null, null);

    public DeliveryOption withPoints(List<JsonNode> pts) {
        return new DeliveryOption(method, carrier, carrierName, pts, eta);
    }

    public DeliveryOption withEta(String value) {
        return new DeliveryOption(method, carrier, carrierName, points, value);
    }
}
