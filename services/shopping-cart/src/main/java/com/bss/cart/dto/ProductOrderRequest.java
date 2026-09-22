package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The TMF622 order this service places for an agent: the same shape the
 * storefront submits, so ordering's cardinality gate sees a channel it
 * already trusts. A configured bundle's children carry the configurator's
 * picks as {@code product.productCharacteristic} — billing rates from exactly
 * these.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"description", "category", "productOrderItem", "relatedParty", "payment"})
public record ProductOrderRequest(String description, String category, List<OrderItem> productOrderItem,
        List<PartyRef> relatedParty, List<EntityRef> payment) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "action", "quantity", "productOffering", "product", "productOrderItem"})
    public record OrderItem(String id, String action, int quantity, EntityRef productOffering, Product product,
            List<OrderItem> productOrderItem) {

        public OrderItem withChildren(List<OrderItem> children, Product own) {
            return new OrderItem(id, action, quantity, productOffering, own, children);
        }
    }

    /** The characteristic block: the picks as the configurator echoed them, untyped past the name/value pair. */
    public record Product(JsonNode productCharacteristic) {
    }
}
