package com.bss.interaction.privacy;

import com.bss.interaction.entity.PartyInteraction;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One shelf of the privacy passport: every interaction this service holds for
 * a person, as stored. Key order is the one the wire already has (count before
 * category) — a salted {@code Map.of} chose it and the old image printed it.
 */
@JsonPropertyOrder({"count", "category", "items"})
public record PrivacyExport(int count, String category, List<PartyInteraction> items) {

    public static PrivacyExport of(String category, List<PartyInteraction> items) {
        return new PrivacyExport(items.size(), category, items);
    }
}
