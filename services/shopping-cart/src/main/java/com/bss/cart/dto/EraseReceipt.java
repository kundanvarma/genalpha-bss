package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What an erasure did here: honest counts, nothing more. */
@JsonPropertyOrder({"category", "deleted", "retained"})
public record EraseReceipt(String category, int deleted, int retained) {
}
