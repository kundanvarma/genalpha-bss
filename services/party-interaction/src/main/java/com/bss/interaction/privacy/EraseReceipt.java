package com.bss.interaction.privacy;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What this service deleted for a person, and what it kept. Honest counts, nothing more. */
@JsonPropertyOrder({"deleted", "category", "retained"})
public record EraseReceipt(int deleted, String category, int retained) {
}
