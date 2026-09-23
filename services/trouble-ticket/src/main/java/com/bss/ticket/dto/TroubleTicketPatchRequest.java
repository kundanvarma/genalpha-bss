package com.bss.ticket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * PATCH troubleTicket: the two legal changes, and nothing else. A severity,
 * a name or an owner in the body was ignored before and cannot even be
 * declared now.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TroubleTicketPatchRequest(JsonNode status, JsonNode note) {
}
