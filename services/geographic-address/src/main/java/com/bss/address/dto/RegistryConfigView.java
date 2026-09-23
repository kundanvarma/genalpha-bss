package com.bss.address.dto;

import com.bss.address.entity.RegistryConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One registry binding. The secret is a reference only — never the value. */
@JsonPropertyOrder({"country", "provider", "displayName", "baseUrl", "secretRef",
        "enabled", "@type"})
public record RegistryConfigView(
        String country,
        String provider,
        String displayName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String baseUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String secretRef,
        boolean enabled,
        @JsonProperty("@type") String type) {

    public static RegistryConfigView of(RegistryConfig c) {
        return new RegistryConfigView(c.getCountry(), c.getProvider(), c.getDisplayName(),
                c.getBaseUrl(), c.getSecretRef(), c.isEnabled(), "RegistryConfig");
    }
}
