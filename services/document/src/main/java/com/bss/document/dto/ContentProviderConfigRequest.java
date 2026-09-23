package com.bss.document.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted CMS binding. Two blocks stay trees because the map path read them
 * leniently: {@code directUrl} was {@code Boolean.TRUE.equals(value)}, so the
 * string "true" never switched it on; {@code config} is stored as-is when it
 * arrives as a JSON string and serialised when it arrives as an object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentProviderConfigRequest(
        String provider,
        String baseUrl,
        String projectId,
        String dataset,
        String secretRef,
        String webhookSecretRef,
        JsonNode directUrl,
        JsonNode config) {

    /** True only for a real JSON {@code true}, as the map comparison was. */
    public boolean directUrlOrFalse() {
        return directUrl != null && directUrl.isBoolean() && directUrl.booleanValue();
    }

    /** The config column: the caller's string verbatim, or their object as JSON. */
    public String configJson() {
        if (config == null || config.isNull()) {
            return null;
        }
        return config.isTextual() ? config.asText() : config.toString();
    }
}
