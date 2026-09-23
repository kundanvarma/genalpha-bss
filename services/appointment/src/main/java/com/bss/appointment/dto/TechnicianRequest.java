package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A roster row as the back office posts or patches it. Sparse like the
 * calendar: an absent key leaves the column alone, and each field keeps the
 * reading the map gave it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TechnicianRequest(JsonNode name, JsonNode skills, JsonNode zone, JsonNode workingDays,
                                JsonNode startTime, JsonNode endTime, JsonNode active) {

    public static final TechnicianRequest EMPTY =
            new TechnicianRequest(null, null, null, null, null, null, null);

    /** Create refused a row with no name at all — an absent key and an explicit null alike. */
    public boolean named() {
        return name != null && !name.isNull() && !Json.valueOf(name).isBlank();
    }
}
