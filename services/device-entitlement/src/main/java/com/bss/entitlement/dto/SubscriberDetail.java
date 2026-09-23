package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;
import java.util.Map;

/**
 * Everything the console's drawer shows for one line: the binding itself,
 * the entitlement decision in words, the same decision in TS.43's terms per
 * application id, and the devices, companion eSIMs and transfers that hang
 * off the line. The binding is unwrapped FIRST, so its keys come before the
 * shelves exactly as the map wrote them.
 */
@JsonPropertyOrder({"subscriber", "entitlements", "ts43", "companions", "devices", "transfers"})
public record SubscriberDetail(
        @JsonUnwrapped SubscriberView subscriber,
        EntitlementExplanation entitlements,
        Map<String, Ts43Block> ts43,
        List<CompanionView> companions,
        List<DeviceView> devices,
        List<TransferView> transfers) {
}
