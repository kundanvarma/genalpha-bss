package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The reachability answer for the tenant's configured field-service provider.
 * The key order is the one the wire already has — the old three-entry
 * {@code Map.of} printed {@code ok, detail, provider}.
 */
@JsonPropertyOrder({"ok", "detail", "provider"})
public record ProbeResult(boolean ok, String detail, String provider) {
}
