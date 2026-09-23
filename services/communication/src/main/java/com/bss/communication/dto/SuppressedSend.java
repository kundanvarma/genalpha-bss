package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Nobody was contacted, and why: the opt-out ledger or the frequency cap. */
@JsonPropertyOrder({"status", "capped", "optedOut", "reason"})
public record SuppressedSend(
        @JsonProperty("status") String status,
        @JsonProperty("capped") int capped,
        @JsonProperty("optedOut") int optedOut,
        @JsonProperty("reason") String reason) implements SendOutcome {

    public static SuppressedSend of(int capped, int optedOut) {
        boolean allOptedOut = optedOut > 0 && capped == 0;
        return new SuppressedSend(allOptedOut ? "suppressed" : "capped", capped, optedOut,
                allOptedOut ? "every recipient has opted out of marketing"
                        : "frequency cap reached for all recipients in the window");
    }
}
