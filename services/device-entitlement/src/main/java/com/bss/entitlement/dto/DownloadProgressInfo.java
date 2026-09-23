package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The SM-DP+'s {@code handleDownloadProgressInfo} body (SGP.22 ES2+
 * §5.3.5). The document carries far more than the ECS reads — the whole
 * function-requester envelope, the profile's own identifiers — so unknown
 * fields are ignored and the four facts the ECS acts on are read exactly as
 * the map read them: {@code String.valueOf} on the identifiers, a lenient
 * parse of the notification point, and the nested status only when the
 * status block is an object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DownloadProgressInfo(JsonNode iccid, JsonNode eid, JsonNode notificationPointId,
        JsonNode notificationPointStatus) {

    public String iccidText() {
        return SubscriberUpsertRequest.scalar(iccid);
    }

    public String eidText() {
        return SubscriberUpsertRequest.scalar(eid);
    }

    /** Notification point 3 = downloading, 4 = installed on the eUICC; anything unreadable is 0. */
    public int point() {
        String raw = notificationPointId == null ? "0" : SubscriberUpsertRequest.scalar(notificationPointId);
        try {
            return Integer.parseInt(raw == null ? "null" : raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code Executed-Success}, or the failure the SM-DP+ reports; null when the block says nothing. */
    public String status() {
        if (notificationPointStatus == null || !notificationPointStatus.isObject()) {
            return null;
        }
        JsonNode status = notificationPointStatus.get("status");
        return status == null || status.isNull() ? null : SubscriberUpsertRequest.scalar(status);
    }
}
