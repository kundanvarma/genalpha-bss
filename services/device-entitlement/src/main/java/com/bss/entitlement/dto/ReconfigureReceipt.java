package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * What a server-initiated re-configuration did (TS.43 §2.6): the line it was
 * asked for, which applications the phones must fetch again, the spec's
 * payload as it was handed to the notification seam, and one target per
 * phone reached. Also the payload of
 * {@code EntitlementReconfigureRequestedEvent} — unchanged in shape.
 */
@JsonPropertyOrder({"subscriber", "apps", "payload", "targets"})
public record ReconfigureReceipt(
        @JsonUnwrapped SubscriberView subscriber,
        List<String> apps,
        String payload,
        List<Target> targets) {

    /**
     * The payload the spec puts on the wire to the phone — an {@code app}
     * list and the moment the refresh was asked for. Key order is the
     * order the notice already carries.
     */
    @JsonPropertyOrder({"app", "timestamp"})
    public record Payload(List<String> app, String timestamp) {
    }

    /**
     * One phone (or, when none has checked in yet, the line's own SMS):
     * {@code terminalId} is off the line-level target, as it always was, and
     * {@code messageId} stays null when nothing was sent.
     */
    @JsonPropertyOrder({"terminalId", "channel", "messageId"})
    public record Target(
            @JsonInclude(JsonInclude.Include.NON_NULL) String terminalId,
            String channel,
            String messageId) {

        public static Target line(String channel, String messageId) {
            return new Target(null, channel, messageId);
        }
    }
}
