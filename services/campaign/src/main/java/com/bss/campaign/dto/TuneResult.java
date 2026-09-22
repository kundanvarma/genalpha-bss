package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The tuner's answer on demand: the ledger row it just wrote, then the journey it belongs to. */
@JsonPropertyOrder({"entry", "journeyId"})
public record TuneResult(@JsonUnwrapped TuneEntry entry, String journeyId) {
}
