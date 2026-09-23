package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Honest counts: what this service deleted, nothing more. Key order as the map printed it. */
@JsonPropertyOrder({"retained", "category", "deleted"})
public record EraseReceipt(int retained, String category, int deleted) {

    public static EraseReceipt of(String category, int deleted) {
        return new EraseReceipt(0, category, deleted);
    }
}
