package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One line of the approval trail. Every key is always present — a null note is a fact, not an absence. */
@JsonPropertyOrder({"action", "actor", "note", "envelopeId", "envelopeName", "at"})
public record LedgerLine(String action, String actor, String note, String envelopeId, String envelopeName, String at) {
}
