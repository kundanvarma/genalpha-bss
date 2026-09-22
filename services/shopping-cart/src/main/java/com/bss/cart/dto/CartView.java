package com.bss.cart.dto;

import com.bss.cart.entity.ShoppingCart;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The TMF663 ShoppingCart as every channel reads it and as every cart event
 * carries it. {@code relatedParty} appears once a cart is claimed,
 * {@code relatedEntity} once it has checked out into an order.
 */
@JsonPropertyOrder({"id", "href", "status", "relatedParty", "cartItem", "relatedEntity", "creationDate",
        "lastUpdate", "@type"})
public record CartView(String id, String href, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        List<CartItem> cartItem,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<EntityRef> relatedEntity,
        OffsetDateTime creationDate, OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static CartView of(ShoppingCart entity, List<CartItem> cartItem, List<EntityRef> relatedEntity) {
        return new CartView(entity.getId(), entity.getHref(), entity.getStatus(),
                entity.getOwnerPartyId() == null ? null : List.of(PartyRef.customer(entity.getOwnerPartyId())),
                cartItem, relatedEntity, entity.getCreatedAt(), entity.getLastUpdate(), "ShoppingCart");
    }
}
