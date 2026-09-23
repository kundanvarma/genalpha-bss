package com.bss.communication.dto;

import com.bss.communication.entity.CommunicationMessage;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** What this service holds about one party — its own shelf of the passport. */
@JsonPropertyOrder({"category", "count", "items"})
public record PrivacyExport(
        @JsonProperty("category") String category,
        @JsonProperty("count") int count,
        @JsonProperty("items") List<CommunicationMessage> items) {
}
