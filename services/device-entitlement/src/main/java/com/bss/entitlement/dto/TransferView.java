package com.bss.entitlement.dto;

import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.entity.SubscriptionTransfer;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A primary eSIM moving to another phone: which phone asked, which profile
 * was ordered, and where the transfer stands. Also the payload of the
 * transfer domain events — unchanged in shape.
 */
@JsonPropertyOrder({"id", "imsi", "msisdn", "partyId", "serviceId", "oldTerminalId", "targetTerminalId",
    "targetEid", "newIccid", "status", "activationCode", "matchingId", "smdpAddress", "profileState",
    "createdAt"})
public record TransferView(
        String id,
        String imsi,
        String msisdn,
        String partyId,
        String serviceId,
        String oldTerminalId,
        String targetTerminalId,
        String targetEid,
        String newIccid,
        String status,
        String activationCode,
        String matchingId,
        String smdpAddress,
        String profileState,
        String createdAt) {

    public static TransferView of(SubscriptionTransfer t, EntitlementSubscriber s) {
        return new TransferView(t.getId(), t.getImsi(), s.getMsisdn(), s.getPartyId(), s.getServiceId(),
                t.getOldTerminalId(), t.getTargetTerminalId(), t.getTargetEid(), t.getNewIccid(),
                t.getStatus(), t.getActivationCode(), t.getMatchingId(), t.getSmdpAddress(),
                t.getProfileState(), t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
    }
}
