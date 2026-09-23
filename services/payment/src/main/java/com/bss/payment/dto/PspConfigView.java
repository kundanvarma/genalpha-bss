package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One row of the operator's PSP menu. The API key is a secret REFERENCE, never the value. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"provider", "displayName", "baseUrl", "secretRef", "methods",
        "isDefault", "priority", "currencies", "enabled", "@type"})
public record PspConfigView(
        @JsonProperty("provider") String provider,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("secretRef") String secretRef,
        @JsonProperty("methods") String methods,
        @JsonProperty("isDefault") boolean isDefault,
        @JsonProperty("priority") int priority,
        @JsonProperty("currencies") String currencies,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("@type") String type) {
}
