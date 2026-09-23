package com.bss.entitlement.service;

import com.bss.entitlement.dto.RcsConfiguration;
import com.bss.entitlement.dto.Ts43Envelope;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    /** The configuration document — rendered by {@link Ts43Xml} in RCC.07's XML shape. */
    public RcsConfiguration configuration(EntitlementSubscriber s, String token, boolean withToken) {
        Map<String, Boolean> f = decisions.features(s);
        boolean live = EntitlementSubscriber.ACTIVE.equals(s.getStatus());
        boolean rcs = live && f.getOrDefault("rcs", false);
        // RCC.14: version 0 = RCS disabled, the client resets to its defaults
        Ts43Envelope.Vers vers = new Ts43Envelope.Vers(String.valueOf(validitySeconds), rcs ? configVersion : "0");
        Ts43Envelope.Token tokenBlock = withToken && token != null
                ? new Ts43Envelope.Token(token, String.valueOf(validitySeconds)) : null;
        if (!rcs) {
            return RcsConfiguration.disabled(vers, tokenBlock);
        }
        TenantRegistry.TenantEntry t = tenants.byId(s.getTenantId());
        String realm = t != null && t.getRcsImsRealm() != null && !t.getRcsImsRealm().isBlank() ? t.getRcsImsRealm() : defaultRealm;
        String pcscf = t != null && t.getRcsPcscf() != null && !t.getRcsPcscf().isBlank() ? t.getRcsPcscf() : defaultPcscf;
        String apn = t != null && t.getRcsApn() != null && !t.getRcsApn().isBlank() ? t.getRcsApn() : defaultApn;
        RcsConfiguration.ImsSettings ims = new RcsConfiguration.ImsSettings("ap2001", "IMS Settings", realm,
                s.getImsi() + "@" + realm,
                new RcsConfiguration.PublicUserIdentityList(
                        "sip:+" + (s.getMsisdn() == null ? "" : s.getMsisdn()) + "@" + realm),
                new RcsConfiguration.PcscfAddress("FQDN", pcscf),
                "AKA", "1",
                new RcsConfiguration.Ext(new RcsConfiguration.ApnConfig(apn), "1"));
        String voice = f.getOrDefault("volte", false) ? "1" : "0";
        RcsConfiguration.RcsSettings services = new RcsConfiguration.RcsSettings("ap2002", "RCS settings",
                new RcsConfiguration.Services("1", "1", "0", "1", "1", voice, voice, "0", "1"),
                new RcsConfiguration.Messaging("1048576", "0", "https://ft." + realm + "/upload"),
                new RcsConfiguration.Presence("0"));
        return new RcsConfiguration(vers, tokenBlock, ims, services);
    }
}
