package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * PATCH shoppingCart: replace the lines, claim the cart for the caller (an
 * empty body does that), or check it out with the order it became.
 * {@code cartItem} is a tree because absent and null mean different things:
 * absent leaves the lines alone, {@code null} clears them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartPatch(JsonNode cartItem, String status, List<EntityRef> relatedEntity) {
}
