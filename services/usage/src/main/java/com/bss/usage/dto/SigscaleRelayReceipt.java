package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** One SigScale hub delivery: {status: accepted, relayed:[...]} or {status: ignored, reason}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"status", "reason", "relayed"})
public record SigscaleRelayReceipt(String status, String reason, List<UsageThresholdNotification> relayed) {

    public static SigscaleRelayReceipt ignored(String reason) {
        return new SigscaleRelayReceipt("ignored", reason, null);
    }

    public static SigscaleRelayReceipt accepted(List<UsageThresholdNotification> relayed) {
        return new SigscaleRelayReceipt("accepted", null, relayed);
    }
}
