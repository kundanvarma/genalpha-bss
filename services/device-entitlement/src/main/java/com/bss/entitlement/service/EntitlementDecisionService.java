package com.bss.entitlement.service;

import com.bss.entitlement.client.CatalogClient;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decision: what is this line entitled to, right now? The PLAN says what
 * was sold (spec characteristics), the LINE says what is live (status, IMS
 * provisioning, emergency address, terms), a per-line OVERRIDE can bar or
 * trial a feature. Rendered in TS.43's own vocabulary per application id:
 *
 * <pre>
 *   ap2003  Voice-over-Cellular (VoLTE 4G / VoNR 5G)      volte, vonr
 *   ap2004  Voice-over-Wi-Fi                               vowifi (+ AddrStatus, TC_Status, ProvStatus)
 *   ap2005  SMS over IP                                    smsoip
 *   ap2010  Data plan information                          dataPlanType (Metered | Unmetered)
 * </pre>
 * EntitlementStatus: 0 DISABLED · 1 ENABLED · 2 INCOMPATIBLE · 3 PROVISIONING.
 * ODSA (ap2006 / ap2009) lives in {@link OdsaService}.
 */
@Service
public class EntitlementDecisionService {

    public static final String DISABLED = "0";
    public static final String ENABLED = "1";
    public static final String INCOMPATIBLE = "2";
    public static final String PROVISIONING = "3";

    private final CatalogClient catalog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String publicBaseUrl;
    private final String privateIdSecret;

    public EntitlementDecisionService(CatalogClient catalog,
            @Value("${bss.entitlement.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${bss.entitlement.private-id-secret:dev-private-id-secret}") String privateIdSecret) {
        this.catalog = catalog;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.privateIdSecret = privateIdSecret;
    }

    /** The effective feature switches for a subscriber: plan, then line overrides. */
    public Map<String, Boolean> features(EntitlementSubscriber s) {
        Map<String, String> plan = catalog.planCharacteristics(s.getTenantId(), s.getOfferingId());
        Map<String, Boolean> f = new LinkedHashMap<>();
        for (String key : List.of("volte", "vonr", "vowifi", "smsoip", "rcs", "companionesim", "esimtransfer",
                "carrierbilling", "satellite", "privateidentity")) {
            f.put(key, truthy(plan.get(key)));
        }
        Map<String, Object> overrides = overrides(s);
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            f.put(e.getKey().toLowerCase(Locale.ROOT), truthy(String.valueOf(e.getValue())));
        }
        return f;
    }

    public String dataPlanType(EntitlementSubscriber s) {
        String v = catalog.planCharacteristics(s.getTenantId(), s.getOfferingId()).get("dataplantype");
        return v == null || v.isBlank() ? "Metered" : v;
    }

    public String planName(EntitlementSubscriber s) {
        return catalog.planCharacteristics(s.getTenantId(), s.getOfferingId()).getOrDefault("_offeringname", s.getOfferingId());
    }

    /** The TS.43 application block for one app id, or null for apps this ECS does not serve. */
    public Map<String, Object> decide(EntitlementSubscriber s, String app, String token) {
        Map<String, Boolean> f = features(s);
        boolean live = EntitlementSubscriber.ACTIVE.equals(s.getStatus());
        switch (app) {
            case "ap2003": {
                Map<String, Object> out = new LinkedHashMap<>();
                List<Map<String, Object>> entries = new java.util.ArrayList<>();
                entries.add(Map.of("RATVoiceEntitleInfoDetails", rat("1", "1", status(f.get("volte"), live, s))));
                Map<String, Object> nr = new LinkedHashMap<>(rat("2", "1", status(f.get("vonr") || f.get("volte"), live, s)));
                if (!f.get("vonr")) {
                    nr.put("NetworkVoiceIRATCapablity", "EPS-Fallback");
                }
                entries.add(Map.of("RATVoiceEntitleInfoDetails", nr));
                out.put("VoiceOverCellularEntitleInfo", entries);
                return out;
            }
            case "ap2004": {
                Map<String, Object> out = new LinkedHashMap<>();
                String st = status(f.get("vowifi"), live, s);
                out.put("EntitlementStatus", st);
                out.put("ServiceFlow_URL", publicBaseUrl + "/ts43/flow/vowifi");
                out.put("ServiceFlow_UserData", "token=" + (token == null ? "" : token) + "&imsi=" + s.getImsi());
                out.put("AddrStatus", s.isEmergencyAddressConfirmed() ? "1" : "0");
                out.put("TC_Status", s.isTermsAccepted() ? "1" : "0");
                out.put("ProvStatus", s.isImsProvisioned() ? "1" : "3");
                if (INCOMPATIBLE.equals(st)) {
                    out.put("MessageForIncompatible", "Wi-Fi calling is not available on this plan.");
                }
                return out;
            }
            case "ap2005": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("EntitlementStatus", status(f.get("smsoip"), live, s));
                return out;
            }
            case "ap2010": {
                String type = dataPlanType(s);
                List<Map<String, Object>> details = new java.util.ArrayList<>();
                for (String access : List.of("1", "2", "3", "4", "5")) {
                    details.add(Map.of("DataPlanInfoDetails", Map.of("AccessType", access, "DataPlanType", type)));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("DataPlanInfo", details);
                return out;
            }
            case "ap2012": { // Direct Carrier Billing (TS.43 §13)
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("EntitlementStatus", status(f.get("carrierbilling"), live, s));
                out.put("TC_Status", s.isTermsAccepted() ? "1" : "0");
                out.put("ServiceFlow_URL", publicBaseUrl + "/ts43/flow/carrier-billing");
                out.put("ServiceFlow_UserData", "imsi=" + s.getImsi());
                return out;
            }
            case "ap2013": { // Private User Identity (TS.43 §12): an encoded identity for Wi-Fi gateways
                Map<String, Object> out = new LinkedHashMap<>();
                String st = status(f.get("privateidentity"), live, s);
                out.put("EntitlementStatus", st);
                if (ENABLED.equals(st)) {
                    out.put("PrivateUserID", privateUserId(s));
                    out.put("PrivateUserIDType", "1");
                    out.put("PrivateUserIDExpiry", java.time.OffsetDateTime.now().plusDays(30).toString());
                }
                return out;
            }
            case "ap2014": { // Phone number (TS.43 §13.1, GetPhoneNumber)
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("MSISDN", s.getMsisdn() == null ? "" : "+" + s.getMsisdn().replaceAll("[^0-9]", ""));
                out.put("OperationResult", s.getMsisdn() == null ? "100" : "1");
                return out;
            }
            case "ap2016": { // SatMode (TS.43 §15)
                Map<String, Object> out = new LinkedHashMap<>();
                String st = status(f.get("satellite"), live, s);
                out.put("EntitlementStatus", st);
                out.put("ServiceFlow_URL", publicBaseUrl + "/ts43/flow/satellite");
                out.put("ServiceFlow_UserData", "imsi=" + s.getImsi());
                if (!f.get("satellite")) {
                    out.put("MessageForIncompatible", "Satellite messaging is not part of this plan.");
                }
                return out;
            }
            default:
                return null;
        }
    }

    /** A per-tenant pseudonym of the IMSI (HMAC-SHA256, base64url) — the Wi-Fi
     * gateway learns a stable identity, never the IMSI itself. */
    String privateUserId(EntitlementSubscriber s) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec((privateIdSecret + ":" + s.getTenantId()).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(s.getImsi().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException("private identity unavailable", e);
        }
    }

    /** A human-language view of the same decision, for the console and the BSS API. */
    public Map<String, Object> explain(EntitlementSubscriber s) {
        Map<String, Boolean> f = features(s);
        boolean live = EntitlementSubscriber.ACTIVE.equals(s.getStatus());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", planName(s));
        out.put("lineStatus", s.getStatus());
        Map<String, String> services = new LinkedHashMap<>();
        services.put("Voice over 4G (VoLTE)", word(status(f.get("volte"), live, s)));
        services.put("Voice over 5G (VoNR)", word(status(f.get("vonr"), live, s)));
        services.put("Wi-Fi calling (VoWiFi)", word(status(f.get("vowifi"), live, s))
                + (f.get("vowifi") && !s.isEmergencyAddressConfirmed() ? " — emergency address still needed" : ""));
        services.put("SMS over IP", word(status(f.get("smsoip"), live, s)));
        services.put("Companion eSIM (watch, tablet)", f.get("companionesim") && live ? "allowed" : "not on this plan");
        services.put("eSIM transfer to a new phone", f.get("esimtransfer") && live ? "allowed" : "not on this plan");
        services.put("Data plan", dataPlanType(s).toLowerCase(Locale.ROOT));
        services.put("RCS messaging", f.get("rcs") && live ? "on (configured by the RCS server)" : "off");
        services.put("Carrier billing (app stores)", word(status(f.get("carrierbilling"), live, s)));
        services.put("Satellite messaging", word(status(f.get("satellite"), live, s)));
        services.put("Private Wi-Fi identity", word(status(f.get("privateidentity"), live, s)));
        out.put("services", services);
        return out;
    }

    private String status(boolean feature, boolean live, EntitlementSubscriber s) {
        if (!feature) {
            return DISABLED;
        }
        if (!live) {
            return DISABLED;
        }
        return s.isImsProvisioned() ? ENABLED : PROVISIONING;
    }

    private static String word(String status) {
        return switch (status) {
            case ENABLED -> "on";
            case PROVISIONING -> "being set up";
            case INCOMPATIBLE -> "not compatible";
            default -> "off";
        };
    }

    private static Map<String, Object> rat(String accessType, String homeRoaming, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AccessType", accessType);
        m.put("HomeRoamingNWType", homeRoaming);
        m.put("EntitlementStatus", status);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> overrides(EntitlementSubscriber s) {
        if (s.getFeatureOverrides() == null || s.getFeatureOverrides().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(s.getFeatureOverrides(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on") || s.equals("included");
    }
}
