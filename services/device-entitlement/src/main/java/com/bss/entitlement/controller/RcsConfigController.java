package com.bss.entitlement.controller;

import com.bss.entitlement.security.TenantScope;
import com.bss.entitlement.service.EcsService;
import com.bss.entitlement.service.RcsConfigService;
import com.bss.entitlement.service.SubscriberService;
import com.bss.entitlement.service.Ts43Xml;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GSMA RCC.14 RCS auto-configuration door: {@code GET /rcs/autoconfig?vers=0&
 * rcs_version=…&rcs_profile=…&client_vendor=…&terminal_id=…&IMSI=…&token=…}
 * (or EAP_ID for the relay). Answers RCC.07's XML by default — RCS clients
 * speak XML — JSON when asked. Anonymous at HTTP level like the TS.43 door:
 * the SIM is the credential.
 */
@RestController
@RequestMapping("/rcs")
public class RcsConfigController {

    private static final String XML = "text/vnd.wap.connectivity-xml";

    private final EcsService ecs;
    private final RcsConfigService rcs;
    private final SubscriberService subscribers;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public RcsConfigController(EcsService ecs, RcsConfigService rcs, SubscriberService subscribers, TenantScope tenantScope) {
        this.ecs = ecs;
        this.rcs = rcs;
        this.subscribers = subscribers;
        this.tenantScope = tenantScope;
    }

    @GetMapping({ "/autoconfig", "/autoconfig/" })
    public ResponseEntity<Object> get(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return answer(normalise(query), null, request);
    }

    @PostMapping({ "/autoconfig", "/autoconfig/" })
    public ResponseEntity<Object> post(@RequestParam Map<String, String> query,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        String relay = null;
        if (body != null && !body.isBlank()) {
            try {
                Map<?, ?> json = mapper.readValue(body, Map.class);
                if (json.get("eap-relay-packet") != null) {
                    relay = String.valueOf(json.get("eap-relay-packet"));
                }
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "body must be JSON"));
            }
        }
        return answer(normalise(query), relay, request);
    }

    private ResponseEntity<Object> answer(Map<String, String> p, String relay, HttpServletRequest request) {
        String tenantId = tenantScope.currentTenantId();
        EcsService.Auth auth = ecs.authenticate(tenantId, p, relay, cookie(request), "RCS configuration", "autoconfig");
        HttpHeaders h = new HttpHeaders();
        if (auth.reply() != null) {
            EcsService.Reply r = auth.reply();
            if (r.location() != null) h.set(HttpHeaders.LOCATION, r.location());
            if (r.setCookie() != null) h.add(HttpHeaders.SET_COOKIE, r.setCookie());
            h.set(HttpHeaders.CONTENT_TYPE, r.contentType());
            return new ResponseEntity<>(r.body(), h, r.status());
        }
        Map<String, Object> doc = rcs.configuration(auth.subscriber(), auth.token(), auth.fresh());
        boolean disabled = "0".equals(((Map<?, ?>) doc.get("Vers")).get("version"));
        subscribers.log(tenantId, p.get("terminal_id"), auth.subscriber().getImsi(), "RCS configuration", "autoconfig",
                "served", disabled ? "RCS disabled for this line (version 0)" : "RCS configuration version " + ((Map<?, ?>) doc.get("Vers")).get("version"));
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains("application/json") && !accept.contains(XML)) {
            h.set(HttpHeaders.CONTENT_TYPE, "application/json");
            return new ResponseEntity<>(doc, h, 200);
        }
        h.set(HttpHeaders.CONTENT_TYPE, XML);
        return new ResponseEntity<>(Ts43Xml.render(doc), h, 200);
    }

    /** RCC.14 names the SIM parameter {@code IMSI}; the shared authentication reads EAP_ID / token
     * and needs a terminal id — the RCS client always sends one. */
    private static Map<String, String> normalise(Map<String, String> query) {
        Map<String, String> p = new LinkedHashMap<>(query);
        if (p.get("EAP_ID") == null && p.get("IMSI") != null && p.get("token") == null) {
            // a bare IMSI is an identity claim, not a proof: it starts the EAP-AKA relay
            p.put("EAP_ID", "0" + p.get("IMSI"));
        }
        p.putIfAbsent("app", "ap2001");
        return p;
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (EcsService.SESSION_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
