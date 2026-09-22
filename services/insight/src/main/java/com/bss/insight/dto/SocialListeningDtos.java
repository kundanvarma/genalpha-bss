package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.Map;

/** Social listening: the sync receipt, mood + share-of-voice, and one scored mention. */
public final class SocialListeningDtos {

    private SocialListeningDtos() {
    }

    @JsonPropertyOrder({"ingested", "enabled"})
    public record SyncReceipt(int ingested, boolean enabled) {
    }

    @JsonPropertyOrder({"total", "sentiment", "byPlatform"})
    public record Summary(int total, Map<String, Integer> sentiment, Map<String, Integer> byPlatform) {
    }

    @JsonPropertyOrder({"id", "platform", "author", "text", "sentiment", "createdAt"})
    public record Mention(String id, String platform, String author, String text, String sentiment, OffsetDateTime createdAt) {
    }
}
