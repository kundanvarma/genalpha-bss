package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Whether this tenant can send a SIM-less client to its own sign-in (TS.43 §2.8.2), and where. */
@JsonPropertyOrder({"available", "clientId", "callback"})
public record OidcDescription(boolean available, String clientId, String callback) {
}
