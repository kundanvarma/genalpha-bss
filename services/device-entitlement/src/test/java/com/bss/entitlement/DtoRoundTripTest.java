package com.bss.entitlement;

import com.bss.entitlement.dto.ComponentDescriptor;
import com.bss.entitlement.dto.DeviceView;
import com.bss.entitlement.dto.EntitlementBlocks;
import com.bss.entitlement.dto.EntitlementExplanation;
import com.bss.entitlement.dto.Es2PlusReply;
import com.bss.entitlement.dto.OdsaBlock;
import com.bss.entitlement.dto.RcsConfiguration;
import com.bss.entitlement.dto.ReconfigureReceipt;
import com.bss.entitlement.dto.ReconfigureRequest;
import com.bss.entitlement.dto.RevokeReceipt;
import com.bss.entitlement.dto.SubscriberDetail;
import com.bss.entitlement.dto.SubscriberUpsertRequest;
import com.bss.entitlement.dto.SubscriberView;
import com.bss.entitlement.dto.Ts43Block;
import com.bss.entitlement.dto.Ts43Envelope;
import com.bss.entitlement.service.Ts43Xml;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes and the key order of the entitlement server's two
 * dialects — the TS.43 / RCC.07 documents phones read, and the house JSON the
 * BSS reads. No Spring, no database; seconds, not forty minutes.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    // ---- the envelope ----

    @Test
    void versAndTokenKeepTheOrderTheWireHas() throws Exception {
        assertThat(write(new Ts43Envelope.Vers("172800", "12.0")))
                .isEqualTo("{\"validity\":\"172800\",\"version\":\"12.0\"}");
        assertThat(write(new Ts43Envelope.Token("t-1", "2592000")))
                .isEqualTo("{\"token\":\"t-1\",\"validity\":\"2592000\"}");
        assertThat(write(new Ts43Envelope.EapRelay("AQI=")))
                .isEqualTo("{\"eap-relay-packet\":\"AQI=\"}");
        assertThat(write(new Ts43Envelope.Refusal("unknown identity")))
                .isEqualTo("{\"error\":\"unknown identity\"}");
    }

    // ---- the entitlement verdicts ----

    @Test
    void voiceOverCellularWritesTheFallbackOnlyOnTheFivegRow() throws Exception {
        EntitlementBlocks.RatDetails lte = new EntitlementBlocks.RatDetails("1", "1", "1");
        EntitlementBlocks.RatDetails nr = new EntitlementBlocks.RatDetails("2", "1", "1").withEpsFallback();
        assertThat(write(new EntitlementBlocks.VoiceOverCellular(List.of(
                new EntitlementBlocks.RatEntry(lte), new EntitlementBlocks.RatEntry(nr)))))
                .isEqualTo("{\"VoiceOverCellularEntitleInfo\":["
                        + "{\"RATVoiceEntitleInfoDetails\":{\"AccessType\":\"1\",\"HomeRoamingNWType\":\"1\","
                        + "\"EntitlementStatus\":\"1\"}},"
                        + "{\"RATVoiceEntitleInfoDetails\":{\"AccessType\":\"2\",\"HomeRoamingNWType\":\"1\","
                        + "\"EntitlementStatus\":\"1\",\"NetworkVoiceIRATCapablity\":\"EPS-Fallback\"}}]}");
    }

    @Test
    void voWifiLeavesTheIncompatibleMessageOffUnlessItApplies() throws Exception {
        assertThat(write(new EntitlementBlocks.VoWifi("1", "http://e/ts43/flow/vowifi",
                "token=t&imsi=242050000000001", "0", "0", "1", null)))
                .isEqualTo("{\"EntitlementStatus\":\"1\",\"ServiceFlow_URL\":\"http://e/ts43/flow/vowifi\","
                        + "\"ServiceFlow_UserData\":\"token=t&imsi=242050000000001\",\"AddrStatus\":\"0\","
                        + "\"TC_Status\":\"0\",\"ProvStatus\":\"1\"}");
        assertThat(write(new EntitlementBlocks.VoWifi("2", "u", "d", "0", "0", "3",
                "Wi-Fi calling is not available on this plan.")))
                .endsWith("\"MessageForIncompatible\":\"Wi-Fi calling is not available on this plan.\"}");
    }

    @Test
    void theOtherVerdictsKeepTheirOwnKeyOrder() throws Exception {
        assertThat(write(new EntitlementBlocks.SmsOverIp("1"))).isEqualTo("{\"EntitlementStatus\":\"1\"}");
        assertThat(write(new EntitlementBlocks.DataPlan(List.of(
                new EntitlementBlocks.DataPlanEntry(new EntitlementBlocks.DataPlanDetails("Metered", "1"))))))
                .isEqualTo("{\"DataPlanInfo\":[{\"DataPlanInfoDetails\":"
                        + "{\"DataPlanType\":\"Metered\",\"AccessType\":\"1\"}}]}");
        assertThat(write(new EntitlementBlocks.CarrierBilling("0", "0", "u", "imsi=1")))
                .isEqualTo("{\"EntitlementStatus\":\"0\",\"TC_Status\":\"0\",\"ServiceFlow_URL\":\"u\","
                        + "\"ServiceFlow_UserData\":\"imsi=1\"}");
        assertThat(write(EntitlementBlocks.PrivateUserIdentity.off("0")))
                .isEqualTo("{\"EntitlementStatus\":\"0\"}");
        assertThat(write(new EntitlementBlocks.PrivateUserIdentity("1", "pseudo", "1", "2026-10-23T08:00Z")))
                .isEqualTo("{\"EntitlementStatus\":\"1\",\"PrivateUserID\":\"pseudo\","
                        + "\"PrivateUserIDType\":\"1\",\"PrivateUserIDExpiry\":\"2026-10-23T08:00Z\"}");
        assertThat(write(new EntitlementBlocks.PhoneNumber("+4741000001", "1")))
                .isEqualTo("{\"MSISDN\":\"+4741000001\",\"OperationResult\":\"1\"}");
        assertThat(write(new EntitlementBlocks.SatMode("0", "u", "imsi=1",
                "Satellite messaging is not part of this plan.")))
                .isEqualTo("{\"EntitlementStatus\":\"0\",\"ServiceFlow_URL\":\"u\","
                        + "\"ServiceFlow_UserData\":\"imsi=1\","
                        + "\"MessageForIncompatible\":\"Satellite messaging is not part of this plan.\"}");
    }

    // ---- ODSA: one record, one set of keys per operation ----

    @Test
    void everyOdsaOperationWritesOnlyItsOwnKeys() throws Exception {
        assertThat(write(new OdsaBlock.Draft().companionEligibility("1", "SharedNumber")
                .operationResult("1").freeze()))
                .isEqualTo("{\"CompanionAppEligibility\":\"1\",\"CompanionDeviceServices\":\"SharedNumber\","
                        + "\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().companionEligibility("0", "SharedNumber")
                .notEnabled("http://e/ts43/flow/not-enabled", "reason=line").operationResult("1").freeze()))
                .isEqualTo("{\"CompanionAppEligibility\":\"0\",\"CompanionDeviceServices\":\"SharedNumber\","
                        + "\"NotEnabledURL\":\"http://e/ts43/flow/not-enabled\",\"NotEnabledUserData\":\"reason=line\","
                        + "\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().subscriptionResult("5")
                .msg("Companion device", "This plan does not include a companion eSIM.")
                .operationResult("1").freeze()))
                .isEqualTo("{\"SubscriptionResult\":\"5\",\"MSG\":{\"message\":"
                        + "\"This plan does not include a companion eSIM.\",\"title\":\"Companion device\"},"
                        + "\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().subscriptionResult("3").operationResult("1").freeze()))
                .isEqualTo("{\"SubscriptionResult\":\"3\",\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().serviceStatus("1").operationResult("1").freeze()))
                .isEqualTo("{\"ServiceStatus\":\"1\",\"OperationResult\":\"1\"}");
        assertThat(write(OdsaBlock.result("101"))).isEqualTo("{\"OperationResult\":\"101\"}");
        assertThat(write(new OdsaBlock.Draft().companionConfigurations(List.of()).operationResult("1").freeze()))
                .isEqualTo("{\"CompanionConfigurations\":[],\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().companionConfigurations(List.of(
                new OdsaBlock.CompanionConfigEntry(new OdsaBlock.CompanionConfiguration(
                        "8947000", "SharedNumber", "1", "351789")))).operationResult("1").freeze()))
                .isEqualTo("{\"CompanionConfigurations\":[{\"CompanionConfiguration\":{\"ICCID\":\"8947000\","
                        + "\"CompanionDeviceService\":\"SharedNumber\",\"ServiceStatus\":\"1\","
                        + "\"CompanionTerminalId\":\"351789\"}}],\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().primaryEligibility("1").operationResult("1").freeze()))
                .isEqualTo("{\"PrimaryAppEligibility\":\"1\",\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().subscriptionResult("1")
                .subscriptionService("http://e/ts43/flow/subscribe", "imsi=1").operationResult("1").freeze()))
                .isEqualTo("{\"SubscriptionResult\":\"1\",\"SubscriptionServiceURL\":\"http://e/ts43/flow/subscribe\","
                        + "\"SubscriptionServiceUserData\":\"imsi=1\",\"OperationResult\":\"1\"}");
        assertThat(write(new OdsaBlock.Draft().primaryConfiguration(
                new OdsaBlock.PrimaryConfiguration(null, "3", "0")).operationResult("1").freeze()))
                .isEqualTo("{\"PrimaryConfiguration\":{\"ICCID\":null,\"ServiceStatus\":\"3\","
                        + "\"PolicyEnabled\":\"0\"},\"OperationResult\":\"1\"}");
    }

    // ---- the RCC.07 document ----

    @Test
    void aDisabledLineGetsTheVersionBlockAlone() throws Exception {
        assertThat(write(RcsConfiguration.disabled(new Ts43Envelope.Vers("2592000", "0"), null)))
                .isEqualTo("{\"Vers\":{\"validity\":\"2592000\",\"version\":\"0\"}}");
        assertThat(write(RcsConfiguration.disabled(new Ts43Envelope.Vers("2592000", "0"),
                new Ts43Envelope.Token("t", "2592000"))))
                .isEqualTo("{\"Vers\":{\"validity\":\"2592000\",\"version\":\"0\"},"
                        + "\"Token\":{\"token\":\"t\",\"validity\":\"2592000\"}}");
    }

    @Test
    void theRcsDocumentKeepsEveryNestedBlocksOrder() throws Exception {
        assertThat(write(rcs())).isEqualTo("{\"Vers\":{\"validity\":\"2592000\",\"version\":\"1\"},"
                + "\"ap2001\":{\"AppID\":\"ap2001\",\"Name\":\"IMS Settings\","
                + "\"Home_network_domain_name\":\"ims.example\",\"Private_User_Identity\":\"242@ims.example\","
                + "\"Public_User_Identity_List\":{\"Public_User_Identity\":\"sip:+47@ims.example\"},"
                + "\"LBO_P-CSCF_Address\":{\"AddressType\":\"FQDN\",\"Address\":\"pcscf.example\"},"
                + "\"AuthType\":\"AKA\",\"Media_type_restriction_policy\":\"1\","
                + "\"Ext\":{\"ApnConfig\":{\"Apn\":\"ims\"},\"rcsVolteSingleRegistration\":\"1\"}},"
                + "\"ap2002\":{\"AppID\":\"ap2002\",\"Name\":\"RCS settings\","
                + "\"SERVICES\":{\"ChatAuth\":\"1\",\"geolocPushAuth\":\"1\",\"rcsIPVideoCallAuth\":\"0\","
                + "\"standaloneMsgAuth\":\"1\",\"GroupChatAuth\":\"1\",\"vsAuth\":\"1\","
                + "\"rcsIPVoiceCallAuth\":\"1\",\"presencePrfl\":\"0\",\"ftAuth\":\"1\"},"
                + "\"MESSAGING\":{\"MaxSize1toM\":\"1048576\",\"ChatRevokeTimer\":\"0\","
                + "\"ftHTTPCSURI\":\"https://ft.ims.example/upload\"},"
                + "\"PRESENCE\":{\"usePresence\":\"0\"}}}");
    }

    /** The XML face renders the same tree, AppID twice on an RCS application, as it always has. */
    @Test
    void theXmlFaceRendersTheSameDocument() throws Exception {
        assertThat(Ts43Xml.render(mapper.valueToTree(rcs())))
                .contains("  <characteristic type=\"VERS\">\n    <parm name=\"validity\" value=\"2592000\"/>\n"
                        + "    <parm name=\"version\" value=\"1\"/>\n  </characteristic>\n")
                .contains("    <parm name=\"AppID\" value=\"ap2001\"/>\n    <parm name=\"AppID\" value=\"ap2001\"/>\n")
                .contains("    <characteristic type=\"LBO_P-CSCF_Address\">\n"
                        + "      <parm name=\"AddressType\" value=\"FQDN\"/>\n"
                        + "      <parm name=\"Address\" value=\"pcscf.example\"/>\n    </characteristic>\n");
    }

    @Test
    void theXmlFaceRepeatsAWrappedListAndSkipsANull() {
        Map<String, Ts43Block> apps = new LinkedHashMap<>();
        apps.put("ap2010", new EntitlementBlocks.DataPlan(List.of(
                new EntitlementBlocks.DataPlanEntry(new EntitlementBlocks.DataPlanDetails("Metered", "1")),
                new EntitlementBlocks.DataPlanEntry(new EntitlementBlocks.DataPlanDetails("Metered", "2")))));
        apps.put("ap2009", new OdsaBlock.Draft()
                .primaryConfiguration(new OdsaBlock.PrimaryConfiguration(null, "1", "0"))
                .operationResult("1").freeze());
        assertThat(Ts43Xml.render(mapper.valueToTree(apps)))
                .isEqualTo("<?xml version=\"1.0\"?>\n<wap-provisioningdoc version=\"1.1\">\n"
                        + "  <characteristic type=\"APPLICATION\">\n"
                        + "    <parm name=\"AppID\" value=\"ap2010\"/>\n"
                        + "    <characteristic type=\"DataPlanInfoDetails\">\n"
                        + "      <parm name=\"DataPlanType\" value=\"Metered\"/>\n"
                        + "      <parm name=\"AccessType\" value=\"1\"/>\n"
                        + "    </characteristic>\n"
                        + "    <characteristic type=\"DataPlanInfoDetails\">\n"
                        + "      <parm name=\"DataPlanType\" value=\"Metered\"/>\n"
                        + "      <parm name=\"AccessType\" value=\"2\"/>\n"
                        + "    </characteristic>\n"
                        + "  </characteristic>\n"
                        + "  <characteristic type=\"APPLICATION\">\n"
                        + "    <parm name=\"AppID\" value=\"ap2009\"/>\n"
                        + "    <characteristic type=\"PrimaryConfiguration\">\n"
                        + "      <parm name=\"ServiceStatus\" value=\"1\"/>\n"
                        + "      <parm name=\"PolicyEnabled\" value=\"0\"/>\n"
                        + "    </characteristic>\n"
                        + "    <parm name=\"OperationResult\" value=\"1\"/>\n"
                        + "  </characteristic>\n</wap-provisioningdoc>\n");
    }

    // ---- the house face ----

    @Test
    void aBindingWritesEveryKeyNullsIncludedAndLeavesOffOnlyTheTwoTheMapLeftOff() throws Exception {
        assertThat(write(view(null, null)))
                .isEqualTo("{\"id\":\"s-1\",\"imsi\":\"242050000000001\",\"msisdn\":null,\"iccid\":null,"
                        + "\"partyId\":null,\"serviceId\":null,\"offeringId\":\"o-1\",\"status\":\"active\","
                        + "\"imsProvisioned\":true,\"emergencyAddressConfirmed\":false,\"termsAccepted\":false,"
                        + "\"createdAt\":\"2026-09-23T08:00Z\",\"lastUpdate\":null}");
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("rcs", "true");
        overrides.put("volte", "true");
        assertThat(write(view(overrides, explanation())))
                .contains("\"termsAccepted\":false,\"featureOverrides\":{\"rcs\":\"true\",\"volte\":\"true\"},"
                        + "\"createdAt\":\"2026-09-23T08:00Z\",\"lastUpdate\":null,\"entitlements\":{")
                .endsWith("\"entitlements\":{\"plan\":\"Mobile 10 GB\",\"lineStatus\":\"active\","
                        + "\"services\":{\"Voice over 4G (VoLTE)\":\"on\"}}}");
    }

    @Test
    void theDetailReadUnwrapsTheBindingBeforeItsShelves() throws Exception {
        Map<String, Ts43Block> ts43 = new LinkedHashMap<>();
        ts43.put("ap2005", new EntitlementBlocks.SmsOverIp("1"));
        assertThat(write(new SubscriberDetail(view(null, null), explanation(), ts43,
                List.of(), List.of(DeviceView.of(device())), List.of())))
                .startsWith("{\"id\":\"s-1\",\"imsi\":\"242050000000001\"")
                .endsWith("\"lastUpdate\":null,"
                        + "\"entitlements\":{\"plan\":\"Mobile 10 GB\",\"lineStatus\":\"active\","
                        + "\"services\":{\"Voice over 4G (VoLTE)\":\"on\"}},"
                        + "\"ts43\":{\"ap2005\":{\"EntitlementStatus\":\"1\"}},"
                        + "\"companions\":[],"
                        + "\"devices\":[{\"id\":\"d-1\",\"terminalId\":\"35123\",\"imsi\":\"242050000000001\","
                        + "\"vendor\":null,\"model\":null,\"swVersion\":null,\"pushRegistered\":false,"
                        + "\"lastApps\":null,\"lastSeenAt\":null}],"
                        + "\"transfers\":[]}");
    }

    @Test
    void theRefreshReceiptNamesTheLineTargetWithoutATerminal() throws Exception {
        assertThat(write(new ReconfigureReceipt(view(null, null), List.of("ap2004"),
                "{\"app\":[\"ap2004\"],\"timestamp\":\"2026-09-23T08:00Z\"}",
                List.of(ReconfigureReceipt.Target.line("sms", null),
                        new ReconfigureReceipt.Target("35123", "push", "m-1")))))
                .endsWith("\"lastUpdate\":null,\"apps\":[\"ap2004\"],"
                        + "\"payload\":\"{\\\"app\\\":[\\\"ap2004\\\"],\\\"timestamp\\\":\\\"2026-09-23T08:00Z\\\"}\","
                        + "\"targets\":[{\"channel\":\"sms\",\"messageId\":null},"
                        + "{\"terminalId\":\"35123\",\"channel\":\"push\",\"messageId\":\"m-1\"}]}");
        assertThat(write(new ReconfigureReceipt.Payload(List.of("ap2003", "ap2004"), "2026-09-23T08:00Z")))
                .isEqualTo("{\"app\":[\"ap2003\",\"ap2004\"],\"timestamp\":\"2026-09-23T08:00Z\"}");
        assertThat(write(new RevokeReceipt("242050000000001", 3)))
                .isEqualTo("{\"imsi\":\"242050000000001\",\"revoked\":3}");
    }

    @Test
    void theEs2PlusReplyNamesWhatItMovedAndKeepsANullIccid() throws Exception {
        assertThat(write(Es2PlusReply.executedSuccess(new Es2PlusReply.ProfileProgress(null, "confirmed"))))
                .isEqualTo("{\"header\":{\"functionExecutionStatus\":{\"status\":\"Executed-Success\"}},"
                        + "\"applied\":{\"iccid\":null,\"profileState\":\"confirmed\"}}");
        assertThat(write(Es2PlusReply.executedSuccess(new Es2PlusReply.ProfileProgress("8947", "installed")
                .withCompanion("c-1").withTransfer("t-1"))))
                .endsWith("\"applied\":{\"iccid\":\"8947\",\"profileState\":\"installed\","
                        + "\"companion\":\"c-1\",\"transfer\":\"t-1\"}}");
    }

    @Test
    void theComponentDescribesItselfInTheShapeTheRegistryCompares() throws Exception {
        assertThat(write(new ComponentDescriptor("device-entitlement", "Tells phones…", List.of("Entitlement"),
                List.of("EntitlementChangedEvent"), "bss.entitlement.events",
                List.of("[GET] /tmf-api/deviceEntitlement/v1/device"), "GenAlphaComponent")))
                .isEqualTo("{\"component\":\"device-entitlement\",\"meaning\":\"Tells phones…\","
                        + "\"manages\":[\"Entitlement\"],\"events\":[\"EntitlementChangedEvent\"],"
                        + "\"topic\":\"bss.entitlement.events\","
                        + "\"routes\":[\"[GET] /tmf-api/deviceEntitlement/v1/device\"],"
                        + "\"@type\":\"GenAlphaComponent\"}");
    }

    // ---- the request bodies: the map's leniency IS the contract ----

    @Test
    void anAbsentKeyAndAnExplicitNullAreDifferentThings() throws Exception {
        SubscriberUpsertRequest absent = mapper.readValue("{\"imsi\":\"242050000000001\"}",
                SubscriberUpsertRequest.class);
        assertThat(absent.has(absent.msisdn())).isFalse();
        SubscriberUpsertRequest cleared = mapper.readValue(
                "{\"imsi\":\"242050000000001\",\"msisdn\":null}", SubscriberUpsertRequest.class);
        assertThat(cleared.has(cleared.msisdn())).isTrue();
        assertThat(cleared.msisdnText()).isNull();
        assertThat(SubscriberUpsertRequest.given(cleared.msisdn())).isFalse();
    }

    @Test
    void aFlagIsOnlyOnForTheWordTrueAndAnIdIsWhateverStringValueOfSaid() throws Exception {
        SubscriberUpsertRequest r = mapper.readValue("{\"imsi\":242050000000001,"
                + "\"imsProvisioned\":\"true\",\"termsAccepted\":1,\"emergencyAddressConfirmed\":true,"
                + "\"unknownToUs\":\"ignored\"}", SubscriberUpsertRequest.class);
        assertThat(r.imsiText()).isEqualTo("242050000000001");
        assertThat(SubscriberUpsertRequest.truthy(r.imsProvisioned())).isTrue();
        assertThat(SubscriberUpsertRequest.truthy(r.termsAccepted())).isFalse();
        assertThat(SubscriberUpsertRequest.truthy(r.emergencyAddressConfirmed())).isTrue();
    }

    @Test
    void theServiceFlowsPostTheirOwnBody() {
        SubscriberUpsertRequest vowifi = SubscriberUpsertRequest.ofFlow("242050000000001", true, true);
        assertThat(vowifi.imsiText()).isEqualTo("242050000000001");
        assertThat(SubscriberUpsertRequest.truthy(vowifi.emergencyAddressConfirmed())).isTrue();
        SubscriberUpsertRequest other = SubscriberUpsertRequest.ofFlow("242050000000001", null, false);
        assertThat(other.emergencyAddressConfirmed()).isNull();
        assertThat(SubscriberUpsertRequest.truthy(other.termsAccepted())).isFalse();
    }

    @Test
    void onlyAJsonArrayOfAppsIsHonoured() throws Exception {
        assertThat(mapper.readValue("{\"apps\":[\"ap2004\",\"ap2010\"]}", ReconfigureRequest.class)
                .appsOrDefault()).containsExactly("ap2004", "ap2010");
        assertThat(mapper.readValue("{\"apps\":\"ap2004\"}", ReconfigureRequest.class).appsOrDefault())
                .containsExactly("ap2003", "ap2004", "ap2005");
        assertThat(ReconfigureRequest.EMPTY.appsOrDefault()).containsExactly("ap2003", "ap2004", "ap2005");
    }

    // ---- fixtures ----

    private static SubscriberView view(Map<String, Object> overrides, EntitlementExplanation explanation) {
        return new SubscriberView("s-1", "242050000000001", null, null, null, null, "o-1", "active",
                true, false, false, overrides, "2026-09-23T08:00Z", null, explanation);
    }

    private static EntitlementExplanation explanation() {
        Map<String, String> services = new LinkedHashMap<>();
        services.put("Voice over 4G (VoLTE)", "on");
        return new EntitlementExplanation("Mobile 10 GB", "active", services);
    }

    private static com.bss.entitlement.entity.EntitlementDevice device() {
        com.bss.entitlement.entity.EntitlementDevice d = new com.bss.entitlement.entity.EntitlementDevice();
        d.setId("d-1");
        d.setTerminalId("35123");
        d.setImsi("242050000000001");
        return d;
    }

    private static RcsConfiguration rcs() {
        return new RcsConfiguration(new Ts43Envelope.Vers("2592000", "1"), null,
                new RcsConfiguration.ImsSettings("ap2001", "IMS Settings", "ims.example", "242@ims.example",
                        new RcsConfiguration.PublicUserIdentityList("sip:+47@ims.example"),
                        new RcsConfiguration.PcscfAddress("FQDN", "pcscf.example"), "AKA", "1",
                        new RcsConfiguration.Ext(new RcsConfiguration.ApnConfig("ims"), "1")),
                new RcsConfiguration.RcsSettings("ap2002", "RCS settings",
                        new RcsConfiguration.Services("1", "1", "0", "1", "1", "1", "1", "0", "1"),
                        new RcsConfiguration.Messaging("1048576", "0", "https://ft.ims.example/upload"),
                        new RcsConfiguration.Presence("0")));
    }
}
