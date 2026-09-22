package com.bss.insight.dto;

import com.bss.insight.entity.VisitorProfile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The GDPR corner: what the behavioural store holds about a person, and what erasure removed. */
public final class PrivacyDtos {

    private PrivacyDtos() {
    }

    @JsonPropertyOrder({"category", "count", "items", "signals"})
    public record Export(String category, int count, List<VisitorProfile> items, int signals) {
    }

    @JsonPropertyOrder({"category", "deleted", "signalsDeleted", "twinKeysDestroyed", "retained"})
    public record Erasure(String category, int deleted, long signalsDeleted, long twinKeysDestroyed, int retained) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErasureRequest(String partyId) {
    }
}
