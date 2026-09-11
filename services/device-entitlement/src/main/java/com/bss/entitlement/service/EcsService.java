package com.bss.entitlement.service;

import com.bss.entitlement.entity.EntitlementDevice;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.entity.EntitlementToken;
import com.bss.entitlement.repository.EntitlementDeviceRepository;
import com.bss.entitlement.repository.EntitlementSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Entitlement Configuration Server door, end to end: identify the
 * device, authenticate the SIM (token, or the EAP-AKA relay), find the
 * line, decide per requested application, answer in TS.43's JSON shape,
 * and write the request down for the operator.
 */
@Service
public class EcsService {

    private static final Logger log = LoggerFactory.getLogger(EcsService.class);
    public static final String RELAY_TYPE = "application/vnd.gsma.eap-relay.v1.0+json";
    public static final String SESSION_COOKIE = "ECS_SESSION";

    /** What the controller sends back. */
    public record Reply(int status, String contentType, Object body, String setCookie, String location) {
        public Reply(int status, String contentType, Object body, String setCookie) {
            this(status, contentType, body, setCookie, null);
        }
    }

    private final EntitlementSubscriberRepository subscribers;
    private final EntitlementDeviceRepository devices;
    private final EapAkaService eap;
    private final TokenService tokens;
    private final EntitlementDecisionService decisions;
    private final OdsaService odsa;
    private final SubscriberService subscriberService;
    private final OidcService oidc;
    private final String entitlementVersion;
    private final long versValidity;

    public EcsService(EntitlementSubscriberRepository subscribers, EntitlementDeviceRepository devices,
            EapAkaService eap, TokenService tokens, EntitlementDecisionService decisions, OdsaService odsa,
            SubscriberService subscriberService, OidcService oidc,
            @Value("${bss.entitlement.entitlement-version:12.0}") String entitlementVersion,
            @Value("${bss.entitlement.vers-validity-seconds:172800}") long versValidity) {
        this.subscribers = subscribers;
        this.devices = devices;
        this.eap = eap;
        this.tokens = tokens;
        this.decisions = decisions;
        this.odsa = odsa;
        this.subscriberService = subscriberService;
        this.oidc = oidc;
        this.entitlementVersion = entitlementVersion;
        this.versValidity = versValidity;
    }

    /**
     * @param tenantId    the operator (from the ECS hostname / X-Tenant-Id)
     * @param p           request parameters (query and/or JSON body), TS.43 names
     * @param relayPacket the EAP relay packet (base64) when the body carried one
     * @param session     the ECS_SESSION cookie value, if any
     */
    @Transactional
    public Reply handle(String tenantId, Map<String, String> p, String relayPacket, String session) {
        String terminalId = p.get("terminal_id");
        List<String> apps = apps(p);
        String operation = p.get("operation");
        if (terminalId == null && p.get("requestor_id") == null) {
            return error(400, "terminal_id (or requestor_id) is required", tenantId, null, null, apps, operation);
        }
        rememberDevice(tenantId, terminalId, p, apps);

        // --- authentication: a token we issued, or the EAP-AKA relay ---
        String imsi = null;
        String token = p.get("token");
        boolean fresh = false;
        if (token != null && !token.isBlank()) {
            Optional<EntitlementToken> t = tokens.resolve(tenantId, token);
            if (t.isPresent()) {
                imsi = t.get().getImsi();
            }
        }
        if (imsi == null) {
            String eapId = p.get("EAP_ID");
            if (eapId == null || eapId.isBlank()) {
                if (oidc.available(tenantId)) {
                    // TS.43 §2.8.2: no SIM access on this client — authenticate the end-user through OIDC
                    subscriberService.log(tenantId, terminalId, null, appNames(apps), operation, "oidc-redirect",
                            "no token and no EAP_ID: sent to the operator's sign-in");
                    return new Reply(302, "text/plain", "", null, oidc.authorizeUrl(tenantId, originalQuery(p)));
                }
                subscriberService.log(tenantId, terminalId, null, appNames(apps), operation, "unauthenticated",
                        "no token and no EAP_ID");
                // TS.43: 511 Network Authentication Required when the client must authenticate
                return new Reply(511, "application/json", Map.of("error", "authentication required: present a token or EAP_ID"), null);
            }
            if (relayPacket == null) {
                Optional<Map<String, String>> start = eap.start(tenantId, eapId);
                if (start.isEmpty()) {
                    subscriberService.log(tenantId, terminalId, EapAkaService.imsiOf(eapId).orElse(null),
                            appNames(apps), operation, "forbidden", "AUC does not know this identity");
                    return new Reply(403, "application/json", Map.of("error", "unknown identity"), null);
                }
                subscriberService.log(tenantId, terminalId, EapAkaService.imsiOf(eapId).orElse(null),
                        appNames(apps), operation, "eap-challenge", "EAP-Request/AKA-Challenge issued");
                return new Reply(200, RELAY_TYPE, Map.of("eap-relay-packet", start.get().get("packet")),
                        SESSION_COOKIE + "=" + start.get().get("session") + "; Path=/; HttpOnly");
            }
            EapAkaService.Outcome outcome = eap.complete(tenantId, session, relayPacket);
            if (!outcome.ok()) {
                subscriberService.log(tenantId, terminalId, outcome.imsi(), appNames(apps), operation,
                        "eap-failed", outcome.reason());
                return new Reply(403, "application/json", Map.of("error", "EAP-AKA failed: " + outcome.reason()), null);
            }
            imsi = outcome.imsi();
            fresh = true;
        }

        // --- the line ---
        Optional<EntitlementSubscriber> sub = subscribers.findByTenantIdAndImsi(tenantId, imsi);
        if (sub.isEmpty()) {
            subscriberService.log(tenantId, terminalId, imsi, appNames(apps), operation, "forbidden",
                    "SIM authenticated but no line is bound to this IMSI");
            return new Reply(403, "application/json", Map.of("error", "no subscription for this identity"), null);
        }
        EntitlementSubscriber s = sub.get();
        if (fresh) {
            token = tokens.issue(tenantId, imsi, terminalId).getToken();
        }
        linkDevice(tenantId, terminalId, imsi);

        // --- the answer ---
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Vers", Map.of("version", entitlementVersion, "validity", String.valueOf(versValidity)));
        if (fresh) {
            body.put("Token", Map.of("token", token, "validity", String.valueOf(tokens.validitySeconds())));
        }
        List<String> served = new ArrayList<>();
        for (String app : apps) {
            Map<String, Object> block;
            if ("ap2006".equals(app) || "ap2009".equals(app)) {
                block = odsa.handle(s, app, p);
            } else {
                block = decisions.decide(s, app, token);
            }
            if (block != null) {
                body.put(app, block);
                served.add(app);
            }
        }
        subscriberService.log(tenantId, terminalId, imsi, appNames(apps), operation, "served",
                summary(body, served));
        return new Reply(200, "application/json", body, null);
    }

    private Reply error(int status, String message, String tenantId, String terminalId, String imsi, List<String> apps, String operation) {
        subscriberService.log(tenantId, terminalId, imsi, appNames(apps), operation, "bad-request", message);
        return new Reply(status, "application/json", Map.of("error", message), null);
    }

    /** The request's own parameters as a query string (what OIDC resumes after sign-in). */
    private static String originalQuery(Map<String, String> p) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (e.getValue() == null || "token".equals(e.getKey()) || "EAP_ID".equals(e.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static String appNames(List<String> apps) {
        List<String> names = new ArrayList<>();
        for (String a : apps) {
            names.add(appName(a));
        }
        return String.join(", ", names);
    }

    private static List<String> apps(Map<String, String> p) {
        List<String> out = new ArrayList<>();
        String raw = p.get("app");
        if (raw != null) {
            for (String a : raw.split(",")) {
                if (!a.isBlank()) {
                    out.add(a.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add("ap2004");
        }
        return out;
    }

    private void rememberDevice(String tenantId, String terminalId, Map<String, String> p, List<String> apps) {
        if (terminalId == null) {
            return;
        }
        EntitlementDevice d = devices.findByTenantIdAndTerminalId(tenantId, terminalId).orElseGet(() -> {
            EntitlementDevice fresh = new EntitlementDevice();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenantId);
            fresh.setTerminalId(terminalId);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        if (p.get("terminal_vendor") != null) d.setVendor(p.get("terminal_vendor"));
        if (p.get("terminal_model") != null) d.setModel(p.get("terminal_model"));
        if (p.get("terminal_sw_version") != null) d.setSwVersion(p.get("terminal_sw_version"));
        if (p.get("notif_token") != null) {
            d.setNotifToken(p.get("notif_token"));
            try {
                d.setNotifAction(p.get("notif_action") == null ? 1 : Integer.parseInt(p.get("notif_action")));
            } catch (NumberFormatException e) {
                d.setNotifAction(1);
            }
        }
        d.setLastApps(appNames(apps));
        d.setLastSeenAt(OffsetDateTime.now());
        devices.save(d);
    }

    private void linkDevice(String tenantId, String terminalId, String imsi) {
        if (terminalId == null) {
            return;
        }
        devices.findByTenantIdAndTerminalId(tenantId, terminalId).ifPresent(d -> {
            if (!imsi.equals(d.getImsi())) {
                d.setImsi(imsi);
                devices.save(d);
            }
        });
    }

    /** TS.43 application ids in the operator's words. */
    public static String appName(String app) {
        return switch (app) {
            case "ap2003" -> "VoLTE";
            case "ap2004" -> "Wi-Fi calling";
            case "ap2005" -> "SMS over IP";
            case "ap2006" -> "companion eSIM";
            case "ap2009" -> "eSIM for this phone";
            case "ap2010" -> "data plan";
            case "ap2011" -> "server-initiated eSIM";
            case "ap2012" -> "phone number";
            default -> app;
        };
    }

    /** The answer, in words: "VoLTE on · Wi-Fi calling on (address needed) · data plan metered". */
    @SuppressWarnings("unchecked")
    private static String summary(Map<String, Object> body, List<String> served) {
        List<String> parts = new ArrayList<>();
        for (String app : served) {
            Object block = body.get(app);
            if (!(block instanceof Map<?, ?> m)) {
                continue;
            }
            String name = appName(app);
            switch (app) {
                case "ap2003" -> {
                    Object info = m.get("VoiceOverCellularEntitleInfo");
                    String st = "off";
                    if (info instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first
                            && first.get("RATVoiceEntitleInfoDetails") instanceof Map<?, ?> d) {
                        st = statusWord(d.get("EntitlementStatus"));
                    }
                    parts.add(name + " " + st);
                }
                case "ap2004" -> {
                    String st = statusWord(m.get("EntitlementStatus"));
                    boolean needsAddress = "on".equals(st) && !"1".equals(String.valueOf(m.get("AddrStatus")));
                    parts.add(name + " " + st + (needsAddress ? " (address needed)" : ""));
                }
                case "ap2005" -> parts.add(name + " " + statusWord(m.get("EntitlementStatus")));
                case "ap2010" -> {
                    Object info = m.get("DataPlanInfo");
                    String type = "";
                    if (info instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first
                            && first.get("DataPlanInfoDetails") instanceof Map<?, ?> d) {
                        type = String.valueOf(d.get("DataPlanType")).toLowerCase();
                    }
                    parts.add(name + " " + type);
                }
                case "ap2006", "ap2009" -> {
                    if (m.get("CompanionAppEligibility") != null || m.get("PrimaryAppEligibility") != null) {
                        Object e = m.get("CompanionAppEligibility") != null ? m.get("CompanionAppEligibility") : m.get("PrimaryAppEligibility");
                        parts.add(name + (String.valueOf(e).equals("1") ? " eligible" : " not eligible"));
                    } else if (m.get("SubscriptionResult") != null) {
                        parts.add(name + " " + switch (String.valueOf(m.get("SubscriptionResult"))) {
                            case "1" -> "sent to the web sheet";
                            case "2" -> "profile ready to download";
                            case "3" -> "done";
                            case "4" -> "profile coming later";
                            case "5" -> "not available on this plan";
                            default -> "answered";
                        });
                    } else if (m.get("CompanionConfigurations") != null) {
                        parts.add(name + " configuration sent");
                    } else if (m.get("ServiceStatus") != null) {
                        parts.add(name + " service " + ("1".equals(String.valueOf(m.get("ServiceStatus"))) ? "activated" : "deactivated"));
                    } else {
                        parts.add(name + " answered");
                    }
                }
                default -> parts.add(name + " answered");
            }
        }
        return String.join(" · ", parts);
    }

    private static String statusWord(Object status) {
        return switch (String.valueOf(status)) {
            case "1" -> "on";
            case "2" -> "not compatible";
            case "3" -> "being set up";
            default -> "off";
        };
    }
}
