package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** How many device tokens a line lost — the next check-in re-runs EAP-AKA. */
@JsonPropertyOrder({"imsi", "revoked"})
public record RevokeReceipt(String imsi, int revoked) {
}
