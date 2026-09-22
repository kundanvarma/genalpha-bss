package com.bss.party.privacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A shelf this component writes itself into a passport or an erasure report
 * (the siblings' shelves arrive as their own documents). Each factory writes
 * only its own facts.
 */
@JsonPropertyOrder({"category", "deleted", "retained", "note", "reason", "error"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryReceipt(
        String category,
        Integer deleted,
        Integer retained,
        String note,
        String reason,
        String error) {

    public static CategoryReceipt exportUnavailable(String category) {
        return new CategoryReceipt(category, null, null, null, null, "unavailable — retry the export");
    }

    public static CategoryReceipt eraseUnavailable(String category) {
        return new CategoryReceipt(category, 0, null, null, null,
                "unavailable — this category is NOT erased; re-run");
    }

    public static CategoryReceipt profileAnonymized() {
        return new CategoryReceipt("profile", 0, 1,
                "anonymized in place — id kept for referential integrity", null, null);
    }

    public static CategoryReceipt retained(String category, String basis) {
        return new CategoryReceipt(category, 0, -1, null, basis, null);
    }
}
