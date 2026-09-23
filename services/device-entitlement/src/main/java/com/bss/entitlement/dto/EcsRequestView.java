package com.bss.entitlement.dto;

import com.bss.entitlement.entity.EcsRequest;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One line of the ECS request log: who asked, for what, and what they were told. */
@JsonPropertyOrder({"id", "terminalId", "imsi", "app", "operation", "outcome", "detail", "createdAt"})
public record EcsRequestView(
        String id,
        String terminalId,
        String imsi,
        String app,
        String operation,
        String outcome,
        String detail,
        String createdAt) {

    public static EcsRequestView of(EcsRequest r) {
        return new EcsRequestView(r.getId(), r.getTerminalId(), r.getImsi(), r.getApp(), r.getOperation(),
                r.getOutcome(), r.getDetail(), r.getCreatedAt().toString());
    }
}
