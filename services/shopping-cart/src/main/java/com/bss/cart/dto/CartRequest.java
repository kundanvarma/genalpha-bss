package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** POST shoppingCart: the lines to open with, or nothing at all (a guest's empty basket). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartRequest(List<CartItem> cartItem) {

    public static final CartRequest EMPTY = new CartRequest(null);
}
