package com.bss.ticket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A note on a ticket. The author and the clock are stamped server-side — a
 * caller sends text and nothing else. Notes are stored as a JSON array in the
 * ticket's own column, so this record is both the write and the read shape;
 * rows written by older images carry exactly these three keys (in whatever
 * order that JVM's {@code Map.of} chose) and re-serialise in this one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"date", "author", "text"})
public record TicketNote(String date, String author, String text) {
}
