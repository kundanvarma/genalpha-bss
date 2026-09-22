package com.bss.cart.dto;

import com.bss.cart.entity.ShoppingCart;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** This service's shelf of a party's data export: the carts it holds for them, as stored. */
@JsonPropertyOrder({"category", "count", "items"})
public record PrivacyExport(String category, int count, List<ShoppingCart> items) {
}
