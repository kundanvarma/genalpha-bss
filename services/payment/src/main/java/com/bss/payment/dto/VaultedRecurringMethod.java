package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The receipt for "sign up for Klarna": the vaulted method, never its token. */
@JsonPropertyOrder({"id", "label", "provider", "@type"})
public record VaultedRecurringMethod(
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonProperty("provider") String provider,
        @JsonProperty("@type") String type) {

    public static VaultedRecurringMethod of(String id, String label, String provider) {
        return new VaultedRecurringMethod(id, label, provider, "VaultedRecurringMethod");
    }
}
