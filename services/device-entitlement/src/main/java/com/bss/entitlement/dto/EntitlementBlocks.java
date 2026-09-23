package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The entitlement verdicts, one record per TS.43 application id. Names and
 * key order are the spec's, pinned to the wire the map path wrote:
 *
 * <pre>
 *   ap2003  Voice-over-Cellular (VoLTE 4G / VoNR 5G)
 *   ap2004  Voice-over-Wi-Fi
 *   ap2005  SMS over IP
 *   ap2010  Data plan information
 *   ap2012  Direct carrier billing
 *   ap2013  Private user identity
 *   ap2014  Phone number
 *   ap2016  SatMode (satellite messaging)
 * </pre>
 *
 * A key the map used to leave off is {@code NON_NULL} on that component
 * alone; every other key was always written, nulls included.
 */
public final class EntitlementBlocks {

    private EntitlementBlocks() {
    }

    /** ap2003 — one entry per radio access technology. */
    @JsonPropertyOrder({"VoiceOverCellularEntitleInfo"})
    public record VoiceOverCellular(
            @JsonProperty("VoiceOverCellularEntitleInfo") List<RatEntry> voiceOverCellularEntitleInfo)
            implements Ts43Block {
    }

    /** The spec wraps every RAT row in its own single-key object. */
    @JsonPropertyOrder({"RATVoiceEntitleInfoDetails"})
    public record RatEntry(@JsonProperty("RATVoiceEntitleInfoDetails") RatDetails ratVoiceEntitleInfoDetails) {
    }

    /** {@code NetworkVoiceIRATCapablity} (the spec's own spelling) rides only on the 5G row without VoNR. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"AccessType", "HomeRoamingNWType", "EntitlementStatus", "NetworkVoiceIRATCapablity"})
    public record RatDetails(
            @JsonProperty("AccessType") String accessType,
            @JsonProperty("HomeRoamingNWType") String homeRoamingNWType,
            @JsonProperty("EntitlementStatus") String entitlementStatus,
            @JsonProperty("NetworkVoiceIRATCapablity") String networkVoiceIRATCapablity) {

        public RatDetails(String accessType, String homeRoamingNWType, String entitlementStatus) {
            this(accessType, homeRoamingNWType, entitlementStatus, null);
        }

        public RatDetails withEpsFallback() {
            return new RatDetails(accessType, homeRoamingNWType, entitlementStatus, "EPS-Fallback");
        }
    }

    /** ap2004 — plus the service flow that collects the emergency address and terms. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"EntitlementStatus", "ServiceFlow_URL", "ServiceFlow_UserData", "AddrStatus",
        "TC_Status", "ProvStatus", "MessageForIncompatible"})
    public record VoWifi(
            @JsonProperty("EntitlementStatus") String entitlementStatus,
            @JsonProperty("ServiceFlow_URL") String serviceFlowUrl,
            @JsonProperty("ServiceFlow_UserData") String serviceFlowUserData,
            @JsonProperty("AddrStatus") String addrStatus,
            @JsonProperty("TC_Status") String tcStatus,
            @JsonProperty("ProvStatus") String provStatus,
            @JsonProperty("MessageForIncompatible") String messageForIncompatible)
            implements Ts43Block {
    }

    /** ap2005 — one verdict, nothing else. */
    @JsonPropertyOrder({"EntitlementStatus"})
    public record SmsOverIp(@JsonProperty("EntitlementStatus") String entitlementStatus) implements Ts43Block {
    }

    /** ap2010 — the plan's metering, repeated per access type. */
    @JsonPropertyOrder({"DataPlanInfo"})
    public record DataPlan(@JsonProperty("DataPlanInfo") List<DataPlanEntry> dataPlanInfo) implements Ts43Block {
    }

    @JsonPropertyOrder({"DataPlanInfoDetails"})
    public record DataPlanEntry(@JsonProperty("DataPlanInfoDetails") DataPlanDetails dataPlanInfoDetails) {
    }

    /** Key order as the wire has it: the type before the access type. */
    @JsonPropertyOrder({"DataPlanType", "AccessType"})
    public record DataPlanDetails(
            @JsonProperty("DataPlanType") String dataPlanType,
            @JsonProperty("AccessType") String accessType) {
    }

    /** ap2012 — pay with the phone bill. */
    @JsonPropertyOrder({"EntitlementStatus", "TC_Status", "ServiceFlow_URL", "ServiceFlow_UserData"})
    public record CarrierBilling(
            @JsonProperty("EntitlementStatus") String entitlementStatus,
            @JsonProperty("TC_Status") String tcStatus,
            @JsonProperty("ServiceFlow_URL") String serviceFlowUrl,
            @JsonProperty("ServiceFlow_UserData") String serviceFlowUserData)
            implements Ts43Block {
    }

    /** ap2013 — the pseudonym a Wi-Fi gateway may know, only when the line is entitled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"EntitlementStatus", "PrivateUserID", "PrivateUserIDType", "PrivateUserIDExpiry"})
    public record PrivateUserIdentity(
            @JsonProperty("EntitlementStatus") String entitlementStatus,
            @JsonProperty("PrivateUserID") String privateUserId,
            @JsonProperty("PrivateUserIDType") String privateUserIdType,
            @JsonProperty("PrivateUserIDExpiry") String privateUserIdExpiry)
            implements Ts43Block {

        public static PrivateUserIdentity off(String status) {
            return new PrivateUserIdentity(status, null, null, null);
        }
    }

    /** ap2014 — GetPhoneNumber. */
    @JsonPropertyOrder({"MSISDN", "OperationResult"})
    public record PhoneNumber(
            @JsonProperty("MSISDN") String msisdn,
            @JsonProperty("OperationResult") String operationResult)
            implements Ts43Block {
    }

    /** ap2016 — satellite messaging. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"EntitlementStatus", "ServiceFlow_URL", "ServiceFlow_UserData", "MessageForIncompatible"})
    public record SatMode(
            @JsonProperty("EntitlementStatus") String entitlementStatus,
            @JsonProperty("ServiceFlow_URL") String serviceFlowUrl,
            @JsonProperty("ServiceFlow_UserData") String serviceFlowUserData,
            @JsonProperty("MessageForIncompatible") String messageForIncompatible)
            implements Ts43Block {
    }
}
