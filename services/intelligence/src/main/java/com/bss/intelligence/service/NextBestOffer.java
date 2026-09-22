package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** One offer and one reason an agent can say out loud; without candidates, only the reason. */
@JsonPropertyOrder({"offer", "reason", "interests", "provider", "model"})
public record NextBestOffer(OfferRef offer, String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> interests,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String model) {
}
