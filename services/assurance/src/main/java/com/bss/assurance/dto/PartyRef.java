package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A party named in a TMF656 problem (who owns it, who reported it when nobody
 * did). Key order as the old three-entry {@code Map.of} printed it — a
 * {@code Map.of} is re-salted on every JVM start, so this was read off the wire.
 */
@JsonPropertyOrder({"name", "id", "role"})
public record PartyRef(String name, String id, String role) {

    public static PartyRef operations(String tenantId) {
        return new PartyRef("network operations", "op-" + tenantId, "operations");
    }

    public static PartyRef monitoringSystem() {
        return new PartyRef("assurance loop", "assurance", "monitoringSystem");
    }
}
