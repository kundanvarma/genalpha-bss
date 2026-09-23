package com.bss.hub.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/**
 * One row of the delivery ledger: never lost, never silent, always
 * accountable. A row that has not failed carries no {@code lastError} —
 * the key is absent, not null — while a dead letter keeps the error that
 * killed it. An envelope whose type could not be read keeps a written
 * null {@code eventType}, as the ledger has always shown it.
 */
@JsonPropertyOrder({"id", "eventType", "status", "attempts", "lastError", "createdAt", "@type"})
public record DeliveryView(
        String id,
        String eventType,
        String status,
        int attempts,
        @JsonInclude(JsonInclude.Include.NON_NULL) String lastError,
        OffsetDateTime createdAt,
        @JsonProperty("@type") String type) {

    public static DeliveryView of(String id, String eventType, String status, int attempts,
            String lastError, OffsetDateTime createdAt) {
        return new DeliveryView(id, eventType, status, attempts, lastError, createdAt,
                "HubDelivery");
    }
}
