package com.bss.entitlement.dto;

import com.bss.entitlement.entity.CompanionDevice;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A companion eSIM (a watch, a tablet) sharing the primary line: the device,
 * the profile it was given, and where that profile got to. Also the payload
 * of the companion domain events — unchanged in shape.
 */
@JsonPropertyOrder({"id", "imsi", "msisdn", "partyId", "serviceId", "companionTerminalId", "eid", "iccid",
    "vendor", "model", "status", "activationCode", "matchingId", "smdpAddress", "profileState",
    "createdAt", "lastUpdate"})
public record CompanionView(
        String id,
        String imsi,
        String msisdn,
        String partyId,
        String serviceId,
        String companionTerminalId,
        String eid,
        String iccid,
        String vendor,
        String model,
        String status,
        String activationCode,
        String matchingId,
        String smdpAddress,
        String profileState,
        String createdAt,
        String lastUpdate) {

    public static CompanionView of(CompanionDevice c, EntitlementSubscriber s) {
        return new CompanionView(c.getId(), c.getImsi(), s.getMsisdn(), s.getPartyId(), s.getServiceId(),
                c.getCompanionTerminalId(), c.getEid(), c.getIccid(), c.getVendor(), c.getModel(),
                c.getStatus(), c.getActivationCode(), c.getMatchingId(), c.getSmdpAddress(), c.getProfileState(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString(),
                c.getLastUpdate() == null ? null : c.getLastUpdate().toString());
    }
}
