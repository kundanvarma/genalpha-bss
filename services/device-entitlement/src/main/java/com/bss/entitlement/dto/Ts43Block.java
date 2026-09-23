package com.bss.entitlement.dto;

/**
 * One TS.43 APPLICATION block, in the spec's own vocabulary. Every block the
 * ECS can answer with is named here: the entitlement decisions this service
 * computes per capability ({@link EntitlementBlocks}) and the On-Device
 * Service Activation answers ({@link OdsaBlock}). The set is finite — the
 * spec names the application ids — so the interface is sealed and a caller
 * can switch over it without a default.
 */
public sealed interface Ts43Block permits
        EntitlementBlocks.VoiceOverCellular,
        EntitlementBlocks.VoWifi,
        EntitlementBlocks.SmsOverIp,
        EntitlementBlocks.DataPlan,
        EntitlementBlocks.CarrierBilling,
        EntitlementBlocks.PrivateUserIdentity,
        EntitlementBlocks.PhoneNumber,
        EntitlementBlocks.SatMode,
        OdsaBlock {
}
