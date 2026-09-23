package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * The same entitlement decision in the operator's words — what the console
 * drawer and the BSS API read. {@code services} is a keyed collection: the
 * key is the service's name as a person says it, the value what it is doing
 * right now ("on", "being set up", "not on this plan").
 */
@JsonPropertyOrder({"plan", "lineStatus", "services"})
public record EntitlementExplanation(String plan, String lineStatus, Map<String, String> services) {
}
