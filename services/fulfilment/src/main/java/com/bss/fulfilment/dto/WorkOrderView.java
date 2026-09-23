package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/** TMF697 workOrder — the installer visit as a resource. */
@JsonPropertyOrder({"id", "href", "productOrderId", "appointment", "state", "place",
        "note", "relatedParty", "createdAt", "@type"})
public record WorkOrderView(
        String id,
        String href,
        String productOrderId,
        @JsonInclude(JsonInclude.Include.NON_NULL) AppointmentRef appointment,
        String state,
        JsonNode place,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        OffsetDateTime createdAt) {

    @JsonProperty("@type")
    public String atType() {
        return "WorkOrder";
    }
}
