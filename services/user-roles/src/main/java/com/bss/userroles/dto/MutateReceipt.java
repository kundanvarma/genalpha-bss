package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Whether a mutation changed the operator's block. */
@JsonPropertyOrder({"id", "mutated"})
public record MutateReceipt(String id, boolean mutated) {
}
