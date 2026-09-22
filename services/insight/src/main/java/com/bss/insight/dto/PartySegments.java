package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** A party's CDP traits (key → value) and the segment names they amount to. */
@JsonPropertyOrder({"partyId", "traits", "segments"})
public record PartySegments(String partyId, Map<String, String> traits, List<String> segments) {
}
