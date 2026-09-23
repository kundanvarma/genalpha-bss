package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Which TS.43 applications the phones should fetch again. {@code apps} stays
 * a {@link JsonNode}: only a JSON array was ever honoured — anything else
 * (a bare string, a number, a null) falls through to the default set, and a
 * typed {@code List<String>} would have started refusing those bodies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconfigureRequest(JsonNode apps) {

    private static final List<String> DEFAULT_APPS = List.of("ap2003", "ap2004", "ap2005");

    public static final ReconfigureRequest EMPTY = new ReconfigureRequest(null);

    public List<String> appsOrDefault() {
        if (apps == null || !apps.isArray()) {
            return DEFAULT_APPS;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode a : apps) {
            out.add(SubscriberUpsertRequest.scalar(a) == null ? "null" : SubscriberUpsertRequest.scalar(a));
        }
        return out;
    }
}
