package com.bss.appointment.dto;

import com.bss.appointment.entity.Appointment;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** What this service holds about one party. Key order as the old {@code Map.of} printed it. */
@JsonPropertyOrder({"items", "category", "count"})
public record PrivacyExport(List<Appointment> items, String category, int count) {

    public static PrivacyExport of(String category, List<Appointment> items) {
        return new PrivacyExport(items, category, items.size());
    }
}
