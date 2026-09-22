package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** POST /social/publish: the post went out, or the tenant has no social handle. */
public sealed interface PublishResult permits PublishResult.Published, PublishResult.NotPublished {

    @JsonPropertyOrder({"published", "id", "permalink", "provider"})
    record Published(boolean published, String id, String permalink, String provider) implements PublishResult {
        public static Published of(String id, String permalink, String provider) {
            return new Published(true, id, permalink, provider);
        }
    }

    @JsonPropertyOrder({"published", "reason"})
    record NotPublished(boolean published, String reason) implements PublishResult {
        public static NotPublished because(String reason) {
            return new NotPublished(false, reason);
        }
    }
}
