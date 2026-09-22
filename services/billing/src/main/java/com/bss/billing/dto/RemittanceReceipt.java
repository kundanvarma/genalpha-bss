package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One bank file in, one honest accounting out. */
@JsonPropertyOrder({"batchRef", "entries", "applied", "unapplied"})
public record RemittanceReceipt(String batchRef, int entries, int applied, int unapplied) {
}
