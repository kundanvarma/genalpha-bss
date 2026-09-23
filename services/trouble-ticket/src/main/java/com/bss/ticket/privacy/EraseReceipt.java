package com.bss.ticket.privacy;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What this service deleted for a person, and what it kept. Honest counts, nothing more. */
@JsonPropertyOrder({"category", "deleted", "retained"})
public record EraseReceipt(String category, int deleted, int retained) {
}
