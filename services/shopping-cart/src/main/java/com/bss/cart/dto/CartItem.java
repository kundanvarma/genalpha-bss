package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One TMF663 cart line. The standard's keys are declared; what a channel
 * adds — the storefront's {@code key}, {@code offeringId}, {@code selections},
 * {@code deal}, its own {@code characteristics} — rides in {@code extensions}
 * in the order it was posted and round-trips untouched. {@code product} is
 * the open characteristic block and stays an open value: nobody behind the
 * wire types a pick.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "action", "quantity", "productOffering", "product"})
public record CartItem(String id, String action, Integer quantity, EntityRef productOffering, Object product,
        @JsonAnyGetter Map<String, Object> extensions) {

    public CartItem {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** Built from the posted map, so a channel's own keys keep their order. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    @SuppressWarnings("unchecked")
    public static CartItem fromMap(Map<String, Object> posted) {
        Map<String, Object> rest = new LinkedHashMap<>(posted);
        Object offering = rest.remove("productOffering");
        return new CartItem(EntityRef.text(rest.remove("id")), EntityRef.text(rest.remove("action")),
                integer(rest.remove("quantity")),
                offering instanceof Map<?, ?> m ? EntityRef.fromMap((Map<String, Object>) m) : null,
                rest.remove("product"), rest);
    }

    /** The line an agent's checkout adds: action, quantity, the offering. */
    public static CartItem add(int quantity, EntityRef productOffering) {
        return new CartItem(null, "add", quantity, productOffering, null, null);
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
