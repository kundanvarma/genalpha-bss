package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A reply the agent may send about a ticket — a draft, never sent by the model. */
@JsonPropertyOrder({"reply", "provider", "model"})
public record TicketReplyDraft(String reply, String provider, String model) {
}
