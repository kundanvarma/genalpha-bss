package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * TMF656 ServiceProblem. {@code originatorParty} is the declaring system's own
 * block, stored verbatim and answered as it was posted — so it stays a tree;
 * the house default beside it is a record. {@code underlyingAlarm} is the one
 * key the map left off, and only for a problem nobody declared.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "status", "affectedObject", "category",
        "priority", "reason", "originatorParty", "responsibleParty", "affectedNumberOfServices",
        "timeRaised", "timeChanged", "statusChangeDate", "underlyingAlarm", "@type"})
public record ServiceProblemView(
        String id,
        String href,
        String name,
        String description,
        String status,
        String affectedObject,
        String category,
        int priority,
        String reason,
        JsonNode originatorParty,
        PartyRef responsibleParty,
        int affectedNumberOfServices,
        String timeRaised,
        String timeChanged,
        String statusChangeDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<AlarmRef> underlyingAlarm) {

    @JsonProperty("@type")
    public String atType() {
        return "ServiceProblem";
    }
}
