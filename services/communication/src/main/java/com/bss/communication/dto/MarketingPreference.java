package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** The customer's own marketing choice — the answer, and the body that sets it. */
public final class MarketingPreference {

    private MarketingPreference() {
    }

    public record View(@JsonProperty("marketingOptOut") boolean marketingOptOut) {
    }

    /** A console posts a boolean, an older form posts the string "true" — both accepted, as before. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(@JsonProperty("optOut") JsonNode optOut) {

        public boolean optedOut() {
            if (optOut == null || optOut.isNull()) {
                return false;
            }
            return optOut.isBoolean() ? optOut.booleanValue() : "true".equalsIgnoreCase(optOut.asText());
        }
    }
}
