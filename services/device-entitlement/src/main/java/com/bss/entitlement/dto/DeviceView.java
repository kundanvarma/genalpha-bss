package com.bss.entitlement.dto;

import com.bss.entitlement.entity.EntitlementDevice;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A phone that has checked in at the ECS door, as the operator sees it. */
@JsonPropertyOrder({"id", "terminalId", "imsi", "vendor", "model", "swVersion", "pushRegistered",
    "lastApps", "lastSeenAt"})
public record DeviceView(
        String id,
        String terminalId,
        String imsi,
        String vendor,
        String model,
        String swVersion,
        boolean pushRegistered,
        String lastApps,
        String lastSeenAt) {

    public static DeviceView of(EntitlementDevice d) {
        return new DeviceView(d.getId(), d.getTerminalId(), d.getImsi(), d.getVendor(), d.getModel(),
                d.getSwVersion(), d.getNotifToken() != null && d.getNotifAction() != null && d.getNotifAction() > 0,
                d.getLastApps(), d.getLastSeenAt() == null ? null : d.getLastSeenAt().toString());
    }
}
