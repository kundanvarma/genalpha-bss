package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the desk shows for one subscriber: the journey, then the inverse
 * order if one was sent, then the pre-migration snapshot rollback reads —
 * the installed product exactly as it stood before the wave touched it, so
 * it stays the document it was written as.
 */
@JsonPropertyOrder({"customer", "rollbackOrderRef", "snapshot", "createdAt", "@type"})
public record MigrationCustomerDetail(
        @JsonUnwrapped MigrationCustomerView customer,
        @JsonInclude(JsonInclude.Include.NON_NULL) String rollbackOrderRef,
        JsonNode snapshot,
        String createdAt,
        @JsonProperty("@type") String type) {
}
