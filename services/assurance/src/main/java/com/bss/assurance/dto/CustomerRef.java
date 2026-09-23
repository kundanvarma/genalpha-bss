package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Whose promise broke. The SLA ledger's own two-entry Map.of printed role first. */
@JsonPropertyOrder({"role", "id"})
public record CustomerRef(String role, String id) {

    public static CustomerRef customer(String id) {
        return new CustomerRef("customer", id);
    }
}
