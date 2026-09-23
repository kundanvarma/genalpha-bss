package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** "What CAN you deliver here?" — answered on the spot, never persisted. */
@JsonPropertyOrder({"id", "state", "instantSync", "searchCriteria",
        "serviceQualificationItem", "@type"})
public record QueryServiceQualificationResult(
        String id,
        String state,
        boolean instantSync,
        SearchCriteria searchCriteria,
        List<QueryItemView> serviceQualificationItem,
        @JsonProperty("@type") String type) {

    public static QueryServiceQualificationResult of(String id, SearchCriteria criteria,
            List<QueryItemView> items) {
        return new QueryServiceQualificationResult(id, "done", true, criteria, items,
                "QueryServiceQualification");
    }
}
