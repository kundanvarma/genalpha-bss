package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * TMF646 searchTimeSlot's answer. {@code @type} sits second, where the map put
 * it; {@code relatedPlace} is echoed back only when the caller sent an object,
 * so it alone is NON_NULL.
 */
@JsonPropertyOrder({"id", "@type", "status", "searchDate", "searchResult", "timezone",
        "provider", "relatedPlace", "availableTimeSlot"})
public record SearchTimeSlotResult(
        String id,
        String status,
        String searchDate,
        String searchResult,
        String timezone,
        String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode relatedPlace,
        List<SlotView> availableTimeSlot) {

    @JsonProperty("@type")
    public String atType() {
        return "SearchTimeSlot";
    }
}
