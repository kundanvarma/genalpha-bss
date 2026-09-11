package com.bss.entitlement.service;

import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GSMA RCC.14 — the RCS client's auto-configuration server, for an operator
 * running its own RCS core. Same transport and authentication as TS.43
 * (HTTP GET with the client's parameters, EAP-AKA relay or token); the answer
 * is an RCC.07 client configuration: a {@code wap-provisioningdoc} whose
 * VERS block carries the configuration version — {@code 0} tells the client
 * RCS is disabled and to reset (a plan without RCS, a suspended line); a
 * positive version carries the IMS access parameters (the tenant's RCS
 * network) and which services are authorised. Where Android RCS runs on a
 * hosted hub (Google Jibe) or Apple's carrier bundle carries it, the same
 * entitlement decision feeds that partner integration instead.
 */
@Service
public class RcsConfigService {

    private final EntitlementDecisionService decisions;
    private final TenantRegistry tenants;
    private final String configVersion;
    private final long validitySeconds;
    private final String defaultRealm;
    private final String defaultPcscf;
    private final String defaultApn;

    public RcsConfigService(EntitlementDecisionService decisions, TenantRegistry tenants,
            @Value("${bss.entitlement.rcs.config-version:1}") String configVersion,
            @Value("${bss.entitlement.rcs.validity-seconds:2592000}") long validitySeconds,
            @Value("${bss.entitlement.rcs.ims-realm:ims.mnc001.mcc001.3gppnetwork.org}") String defaultRealm,
            @Value("${bss.entitlement.rcs.pcscf:pcscf.ims.example.net}") String defaultPcscf,
            @Value("${bss.entitlement.rcs.apn:ims}") String defaultApn) {
        this.decisions = decisions;
        this.tenants = tenants;
        this.configVersion = configVersion;
        this.validitySeconds = validitySeconds;
        this.defaultRealm = defaultRealm;
        this.defaultPcscf = defaultPcscf;
        this.defaultApn = defaultApn;
    }

    /** The configuration document as a map — rendered by {@link Ts43Xml} in RCC.07's XML shape. */
    public Map<String, Object> configuration(EntitlementSubscriber s, String token, boolean withToken) {
        Map<String, Boolean> f = decisions.features(s);
        boolean live = EntitlementSubscriber.ACTIVE.equals(s.getStatus());
        boolean rcs = live && f.getOrDefault("rcs", false);
        Map<String, Object> doc = new LinkedHashMap<>();
        // RCC.14: version 0 = RCS disabled, the client resets to its defaults
        doc.put("Vers", Map.of("version", rcs ? configVersion : "0", "validity", String.valueOf(validitySeconds)));
        if (withToken && token != null) {
            doc.put("Token", Map.of("token", token, "validity", String.valueOf(validitySeconds)));
        }
        if (!rcs) {
            return doc;
        }
        TenantRegistry.TenantEntry t = tenants.byId(s.getTenantId());
        String realm = t != null && t.getRcsImsRealm() != null && !t.getRcsImsRealm().isBlank() ? t.getRcsImsRealm() : defaultRealm;
        String pcscf = t != null && t.getRcsPcscf() != null && !t.getRcsPcscf().isBlank() ? t.getRcsPcscf() : defaultPcscf;
        String apn = t != null && t.getRcsApn() != null && !t.getRcsApn().isBlank() ? t.getRcsApn() : defaultApn;
        Map<String, Object> ims = new LinkedHashMap<>();
        ims.put("AppID", "ap2001");
        ims.put("Name", "IMS Settings");
        ims.put("Home_network_domain_name", realm);
        ims.put("Private_User_Identity", s.getImsi() + "@" + realm);
        ims.put("Public_User_Identity_List", Map.of("Public_User_Identity", "sip:+" + (s.getMsisdn() == null ? "" : s.getMsisdn()) + "@" + realm));
        ims.put("LBO_P-CSCF_Address", Map.of("Address", pcscf, "AddressType", "FQDN"));
        ims.put("AuthType", "AKA");
        ims.put("Media_type_restriction_policy", "1");
        ims.put("Ext", Map.of("rcsVolteSingleRegistration", "1", "ApnConfig", Map.of("Apn", apn)));
        doc.put("ap2001", ims);
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("AppID", "ap2002");
        services.put("Name", "RCS settings");
        services.put("SERVICES", Map.of(
                "ChatAuth", "1", "GroupChatAuth", "1", "ftAuth", "1", "standaloneMsgAuth", "1",
                "geolocPushAuth", "1", "vsAuth", f.getOrDefault("volte", false) ? "1" : "0",
                "presencePrfl", "0", "rcsIPVoiceCallAuth", f.getOrDefault("volte", false) ? "1" : "0",
                "rcsIPVideoCallAuth", "0"));
        services.put("MESSAGING", Map.of("ChatRevokeTimer", "0", "MaxSize1toM", "1048576", "ftHTTPCSURI",
                "https://ft." + realm + "/upload"));
        services.put("PRESENCE", Map.of("usePresence", "0"));
        doc.put("ap2002", services);
        return doc;
    }
}
