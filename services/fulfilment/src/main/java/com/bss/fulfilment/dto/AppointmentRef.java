package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The installer visit this work order was born from. Key order as the map printed it. */
@JsonPropertyOrder({"@referredType", "id"})
public record AppointmentRef(@JsonProperty("@referredType") String referredType, String id) {

    public static AppointmentRef of(String id) {
        return new AppointmentRef("Appointment", id);
    }
}
