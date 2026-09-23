package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A named carrier's pickup point, normalised to the four facts a shopper picks
 * from. A vendor adapter maps its own document into this and renders it as a
 * tree; the generic HTTP adapter passes the vendor's document through untouched,
 * because there the carrier's own shape IS the contract — so the seam's
 * projection is a list of trees and this record is what fills it when we know
 * the carrier.
 */
@JsonPropertyOrder({"id", "name", "address", "openingHours"})
public record PickupPoint(JsonNode id, JsonNode name, JsonNode address, JsonNode openingHours) {
}
