package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The GSMA RCC.07 client configuration document (served over the RCC.14
 * door): the version block, an optional token, the IMS access parameters
 * (ap2001) and the authorised RCS services (ap2002). A disabled line gets
 * the version block alone — {@code Vers.version} 0 tells the client to
 * reset — so everything below it is {@code NON_NULL}.
 *
 * <p>Every nested block's key order is the order the wire already had: the
 * {@code Map.of} tables this replaces were re-salted on every JVM start, and
 * these are the orders the live document shows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"Vers", "Token", "ap2001", "ap2002"})
public record RcsConfiguration(
        @JsonProperty("Vers") Ts43Envelope.Vers vers,
        @JsonProperty("Token") Ts43Envelope.Token token,
        @JsonProperty("ap2001") ImsSettings imsSettings,
        @JsonProperty("ap2002") RcsSettings rcsSettings) {

    /** The version block alone: RCS is off for this line. */
    public static RcsConfiguration disabled(Ts43Envelope.Vers vers, Ts43Envelope.Token token) {
        return new RcsConfiguration(vers, token, null, null);
    }

    @JsonPropertyOrder({"AppID", "Name", "Home_network_domain_name", "Private_User_Identity",
        "Public_User_Identity_List", "LBO_P-CSCF_Address", "AuthType", "Media_type_restriction_policy", "Ext"})
    public record ImsSettings(
            @JsonProperty("AppID") String appId,
            @JsonProperty("Name") String name,
            @JsonProperty("Home_network_domain_name") String homeNetworkDomainName,
            @JsonProperty("Private_User_Identity") String privateUserIdentity,
            @JsonProperty("Public_User_Identity_List") PublicUserIdentityList publicUserIdentityList,
            @JsonProperty("LBO_P-CSCF_Address") PcscfAddress pcscfAddress,
            @JsonProperty("AuthType") String authType,
            @JsonProperty("Media_type_restriction_policy") String mediaTypeRestrictionPolicy,
            @JsonProperty("Ext") Ext ext) {
    }

    @JsonPropertyOrder({"Public_User_Identity"})
    public record PublicUserIdentityList(@JsonProperty("Public_User_Identity") String publicUserIdentity) {
    }

    @JsonPropertyOrder({"AddressType", "Address"})
    public record PcscfAddress(
            @JsonProperty("AddressType") String addressType,
            @JsonProperty("Address") String address) {
    }

    @JsonPropertyOrder({"ApnConfig", "rcsVolteSingleRegistration"})
    public record Ext(
            @JsonProperty("ApnConfig") ApnConfig apnConfig,
            @JsonProperty("rcsVolteSingleRegistration") String rcsVolteSingleRegistration) {
    }

    @JsonPropertyOrder({"Apn"})
    public record ApnConfig(@JsonProperty("Apn") String apn) {
    }

    @JsonPropertyOrder({"AppID", "Name", "SERVICES", "MESSAGING", "PRESENCE"})
    public record RcsSettings(
            @JsonProperty("AppID") String appId,
            @JsonProperty("Name") String name,
            @JsonProperty("SERVICES") Services services,
            @JsonProperty("MESSAGING") Messaging messaging,
            @JsonProperty("PRESENCE") Presence presence) {
    }

    @JsonPropertyOrder({"ChatAuth", "geolocPushAuth", "rcsIPVideoCallAuth", "standaloneMsgAuth",
        "GroupChatAuth", "vsAuth", "rcsIPVoiceCallAuth", "presencePrfl", "ftAuth"})
    public record Services(
            @JsonProperty("ChatAuth") String chatAuth,
            @JsonProperty("geolocPushAuth") String geolocPushAuth,
            @JsonProperty("rcsIPVideoCallAuth") String rcsIpVideoCallAuth,
            @JsonProperty("standaloneMsgAuth") String standaloneMsgAuth,
            @JsonProperty("GroupChatAuth") String groupChatAuth,
            @JsonProperty("vsAuth") String vsAuth,
            @JsonProperty("rcsIPVoiceCallAuth") String rcsIpVoiceCallAuth,
            @JsonProperty("presencePrfl") String presencePrfl,
            @JsonProperty("ftAuth") String ftAuth) {
    }

    @JsonPropertyOrder({"MaxSize1toM", "ChatRevokeTimer", "ftHTTPCSURI"})
    public record Messaging(
            @JsonProperty("MaxSize1toM") String maxSize1toM,
            @JsonProperty("ChatRevokeTimer") String chatRevokeTimer,
            @JsonProperty("ftHTTPCSURI") String ftHttpCsUri) {
    }

    @JsonPropertyOrder({"usePresence"})
    public record Presence(@JsonProperty("usePresence") String usePresence) {
    }
}
