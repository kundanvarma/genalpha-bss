package com.bss.entitlement.controller;

import com.bss.entitlement.api.ApiConstants;
import com.bss.entitlement.security.TenantScope;
import com.bss.entitlement.service.EcsService;
import com.bss.entitlement.service.SubscriberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * GSMA TS.43 Entitlement Configuration Server — the door phones knock on.
 * {@code GET /ts43?terminal_id=…&app=ap2004&EAP_ID=…} or {@code POST /ts43}
 * with the same parameters as JSON; the EAP relay round trips as
 * {@code application/vnd.gsma.eap-relay.v1.0+json} with an ECS_SESSION cookie.
 * The operator is the hostname the phone reached (X-Tenant-Id from the edge)
 * or the deployment's default tenant.
 */
@RestController
@RequestMapping(ApiConstants.TS43_PATH)
public class Ts43Controller {

    private final EcsService ecs;
    private final TenantScope tenantScope;
    private final SubscriberService subscribers;
    private final ObjectMapper mapper = new ObjectMapper();

    public Ts43Controller(EcsService ecs, TenantScope tenantScope, SubscriberService subscribers) {
        this.ecs = ecs;
        this.tenantScope = tenantScope;
        this.subscribers = subscribers;
    }

    @GetMapping({ "", "/" })
    public ResponseEntity<Object> get(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return reply(ecs.handle(tenantScope.currentTenantId(), multi(query, request), null, cookie(request)));
    }

    @PostMapping({ "", "/" })
    public ResponseEntity<Object> post(@RequestParam Map<String, String> query,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        Map<String, String> params = multi(query, request);
        String relay = null;
        if (body != null && !body.isBlank()) {
            try {
                Map<?, ?> json = mapper.readValue(body, Map.class);
                Object packet = json.get("eap-relay-packet");
                if (packet != null) {
                    relay = String.valueOf(packet);
                } else {
                    // TS.43 POST form: the same parameters as a JSON object ("app" may be an array)
                    for (Map.Entry<?, ?> e : json.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof java.util.List<?> list) {
                            params.put(String.valueOf(e.getKey()), String.join(",", list.stream().map(String::valueOf).toList()));
                        } else if (v != null) {
                            params.put(String.valueOf(e.getKey()), String.valueOf(v));
                        }
                    }
                }
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "body must be JSON"));
            }
        }
        return reply(ecs.handle(tenantScope.currentTenantId(), params, relay, cookie(request)));
    }

    /** The VoWiFi service flow (TS.43 ServiceFlow_URL): the emergency address + terms websheet.
     * A real deployment renders the operator's page; here the flow completes on POST. */
    @GetMapping("/flow/vowifi")
    public ResponseEntity<String> vowifiFlow() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("<!doctype html><title>Wi-Fi calling</title>"
                + "<h1>Wi-Fi calling</h1><p>Confirm the address emergency services should use when you call over Wi-Fi, "
                + "and accept the terms.</p><form method=post><input name=street placeholder='Street and number' required> "
                + "<input name=postcode placeholder='Postcode' required> <label><input type=checkbox name=terms required> I accept the terms</label> "
                + "<button>Confirm</button></form>");
    }

    @PostMapping("/flow/vowifi")
    public ResponseEntity<Object> vowifiFlowDone(@RequestParam Map<String, String> form) {
        String imsi = form.get("imsi");
        if (imsi == null || imsi.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "imsi is required (ServiceFlow_UserData)"));
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("imsi", imsi);
        dto.put("emergencyAddressConfirmed", true);
        dto.put("termsAccepted", form.get("terms") != null);
        return ResponseEntity.ok(subscribers.upsert(dto));
    }

    private static ResponseEntity<Object> reply(EcsService.Reply r) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_TYPE, r.contentType());
        if (r.setCookie() != null) {
            h.add(HttpHeaders.SET_COOKIE, r.setCookie());
        }
        return new ResponseEntity<>(r.body(), h, r.status());
    }

    /** Query parameters, with a repeated {@code app} joined by commas (TS.43 allows app=… several times). */
    private static Map<String, String> multi(Map<String, String> query, HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>(query);
        String[] apps = request.getParameterValues("app");
        if (apps != null && apps.length > 1) {
            out.put("app", String.join(",", apps));
        }
        return out;
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
