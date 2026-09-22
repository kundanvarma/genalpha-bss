package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** CDP backfill: {traits:[{partyId,key,value,multi?}]}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackfillRequest(List<TraitRow> traits) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TraitRow(String partyId, String key, String value, Boolean multi) {
    }

    @JsonPropertyOrder({"written", "skipped"})
    public record Receipt(int written, int skipped) {
    }
}
