package com.bss.bridge;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the bridge tells the foreign BSS it did with an event. Two honest
 * answers, so two arms rather than one record with half its keys null: the
 * event was normalised and republished, or its type is not in this source's
 * mapping and the bridge stayed silent (never an error — an unmapped event
 * is a fact about the config, not a fault of the caller).
 *
 * <p>Both key orders are pinned to what the wire already carried: a
 * {@code Map.of} re-salts its iteration order on every JVM start, so these
 * were read off the running container, not off the declaration.</p>
 */
public sealed interface BridgeReceipt permits BridgeReceipt.Forwarded, BridgeReceipt.Ignored {

    @JsonPropertyOrder({"eventType", "status", "topic", "tenantId"})
    record Forwarded(String eventType, String status, String topic, String tenantId)
            implements BridgeReceipt {

        public static Forwarded of(String eventType, String topic, String tenantId) {
            return new Forwarded(eventType, "forwarded", topic, tenantId);
        }
    }

    @JsonPropertyOrder({"reason", "status"})
    record Ignored(String reason, String status) implements BridgeReceipt {

        public static Ignored unmapped(String foreignType) {
            return new Ignored("unmapped foreign event type '" + foreignType + "'", "ignored");
        }
    }
}
