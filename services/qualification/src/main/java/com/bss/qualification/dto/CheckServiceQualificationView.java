package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A persisted check. The place is the caller's own block, stored and answered
 * verbatim, so it stays an open map behind the typed envelope.
 */
@JsonPropertyOrder({"id", "href", "state", "qualificationResult", "place",
        "serviceQualificationItem", "checkServiceQualificationDate", "@type"})
public record CheckServiceQualificationView(
        String id,
        String href,
        String state,
        String qualificationResult,
        Map<String, Object> place,
        List<CheckItemView> serviceQualificationItem,
        OffsetDateTime checkServiceQualificationDate,
        @JsonProperty("@type") String type) {

    public static CheckServiceQualificationView of(String id, String href, String state,
            String qualificationResult, Map<String, Object> place,
            List<CheckItemView> items, OffsetDateTime date) {
        return new CheckServiceQualificationView(id, href, state, qualificationResult,
                place, items, date, "CheckServiceQualification");
    }
}
