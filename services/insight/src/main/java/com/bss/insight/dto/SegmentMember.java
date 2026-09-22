package com.bss.insight.dto;

/** One known customer in a segment — what the campaign engine and SOM read at send time. */
public record SegmentMember(String partyId) {
}
