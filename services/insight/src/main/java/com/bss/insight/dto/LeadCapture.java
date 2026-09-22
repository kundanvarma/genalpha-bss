package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A lead submit has three honest answers: no such page, not captured (and why), captured with its campaign source. */
public sealed interface LeadCapture permits LeadCapture.NotFound, LeadCapture.Rejected, LeadCapture.Captured {

    @JsonPropertyOrder({"status"})
    record NotFound(String status) implements LeadCapture {
        public static NotFound page() {
            return new NotFound("not_found");
        }
    }

    @JsonPropertyOrder({"status", "captured", "reason"})
    record Rejected(String status, boolean captured, String reason) implements LeadCapture {
        public static Rejected declined() {
            return new Rejected("declined", false, "consent is required to capture a lead");
        }

        public static Rejected invalid(String reason) {
            return new Rejected("invalid", false, reason);
        }
    }

    @JsonPropertyOrder({"status", "captured", "source"})
    record Captured(String status, boolean captured, String source) implements LeadCapture {
        public static Captured from(String source) {
            return new Captured("captured", true, source);
        }
    }
}
