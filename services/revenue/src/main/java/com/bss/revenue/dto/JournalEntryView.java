package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;
import java.util.List;

/** A balanced double-entry posting. An entry with no party simply omits the reference. */
@JsonPropertyOrder({"id", "entryDate", "sourceRef", "sourceType", "description", "currency",
        "relatedParty", "lines", "@type"})
public record JournalEntryView(
        @JsonProperty("id") String id,
        @JsonProperty("entryDate") LocalDate entryDate,
        @JsonProperty("sourceRef") String sourceRef,
        @JsonProperty("sourceType") String sourceType,
        @JsonProperty("description") String description,
        @JsonProperty("currency") String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("relatedParty") List<PartyRef> relatedParty,
        @JsonProperty("lines") List<JournalLineView> lines,
        @JsonProperty("@type") String type) {
}
