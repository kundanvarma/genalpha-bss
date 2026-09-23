package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one legal change to a booking. The map compared the posted value with the
 * string "cancelled", so only a JSON string ever matched — a number or a
 * boolean fell through to the same 400, and still does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppointmentPatch(JsonNode status) {

    public boolean cancelling(String cancelled) {
        return status != null && status.isTextual() && cancelled.equals(status.asText());
    }
}
