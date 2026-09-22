package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A country's e-invoice profile as a config row. The code IS the public identity. */
@JsonPropertyOrder({"id", "code", "name", "syntax", "customizationId", "profileId", "paymentReference",
        "lastUpdate", "@type"})
public record BillFormatProfileView(String id, String code, String name, String syntax, String customizationId,
        String profileId, boolean paymentReference, String lastUpdate,
        @JsonProperty("@type") String type) {
}
