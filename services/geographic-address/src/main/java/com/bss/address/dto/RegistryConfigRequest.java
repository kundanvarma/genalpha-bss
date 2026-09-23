package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted registry binding. {@code enabled} stays a tree because the map
 * path defaulted it with {@code Boolean.FALSE.equals(value)} — only a real
 * JSON {@code false} disables a binding, and the string "false" never did.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryConfigRequest(
        String provider,
        String country,
        String displayName,
        String baseUrl,
        String secretRef,
        JsonNode enabled) {

    /** True unless the caller sent a JSON boolean {@code false}. */
    public boolean enabledOrDefault() {
        return !(enabled != null && enabled.isBoolean() && !enabled.booleanValue());
    }
}
