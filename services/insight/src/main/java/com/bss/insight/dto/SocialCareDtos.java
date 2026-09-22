package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.Map;

/** Social care: the sync receipt, the queue health, and one triaged direct message. */
public final class SocialCareDtos {

    private SocialCareDtos() {
    }

    @JsonPropertyOrder({"ingested", "ticketsRequested", "enabled"})
    public record SyncReceipt(int ingested, int ticketsRequested, boolean enabled) {
    }

    @JsonPropertyOrder({"total", "needCare", "sentiment"})
    public record Summary(int total, int needCare, Map<String, Integer> sentiment) {
    }

    @JsonPropertyOrder({"id", "platform", "author", "handle", "text", "sentiment", "needsCare", "ticketRequested", "createdAt"})
    public record QueueItem(String id, String platform, String author, String handle, String text, String sentiment,
            boolean needsCare, boolean ticketRequested, OffsetDateTime createdAt) {
    }
}
