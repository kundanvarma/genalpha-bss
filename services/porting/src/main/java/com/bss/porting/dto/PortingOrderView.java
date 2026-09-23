package com.bss.porting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A port as the channels and the orchestrator read it. Every clock is the
 * entity's own {@code toString()}, as the map path wrote them, so the text
 * on the wire does not move.
 */
@JsonPropertyOrder({"id", "href", "direction", "phoneNumber", "country", "otherOperator",
        "status", "clearinghouse", "regulator", "rejectReason", "requestedCutover",
        "scheduledCutover", "completedAt", "productOrder", "relatedParty", "@type"})
public record PortingOrderView(
        String id,
        String href,
        String direction,
        String phoneNumber,
        String country,
        @JsonInclude(JsonInclude.Include.NON_NULL) String otherOperator,
        String status,
        String clearinghouse,
        String regulator,
        @JsonInclude(JsonInclude.Include.NON_NULL) String rejectReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String requestedCutover,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scheduledCutover,
        @JsonInclude(JsonInclude.Include.NON_NULL) String completedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef productOrder,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        @JsonProperty("@type") String type) {
}
