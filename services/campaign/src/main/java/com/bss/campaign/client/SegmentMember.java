package com.bss.campaign.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The one fact the engine needs about a segment member — insight's other fields are its own business. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SegmentMember(String partyId) {
}
