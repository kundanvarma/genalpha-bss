package com.bss.entitlement.client;

import com.bss.entitlement.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ES2+ over JSON (SGP.22 §6.5): {@code POST <base>/gsma/rsp2/es2plus/<function>}
 * with the mandatory {@code header {functionRequesterIdentifier,
 * functionCallIdentifier}}; the answer's {@code header.functionExecutionStatus
 * .status} is {@code Executed-Success} or {@code Failed}. Production adds
 * mutual TLS and {@code X-Admin-Protocol}; per tenant: {@code smdp-base-url},
 * {@code smdp-requester-id} (what the SM-DP+ knows this operator as — also
 * the tenant segment of our notification URL) and {@code smdp-token}.
 */
@Component
public class RestSmdpClient implements SmdpClient {

    private static final Logger log = LoggerFactory.getLogger(RestSmdpClient.class);
    private static final String ES2 = "/gsma/rsp2/es2plus/";

    private final RestClient.Builder builder;
    private final TenantRegistry tenants;
    private final String defaultBaseUrl;
    private final String defaultToken;
    private final String defaultAddress;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public RestSmdpClient(RestClient.Builder builder, TenantRegistry tenants,
            @Value("${bss.downstream.smdp-base-url:}") String defaultBaseUrl,
            @Value("${bss.downstream.smdp-token:}") String defaultToken,
            @Value("${bss.entitlement.smdp-address:rsp.example.net}") String defaultAddress) {
        this.builder = builder;
        this.tenants = tenants;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
        this.defaultToken = defaultToken == null ? "" : defaultToken;
        this.defaultAddress = defaultAddress;
    }

    private record Binding(String baseUrl, String requesterId, String token, String address) { }

    private Binding binding(String tenantId) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        boolean own = t != null && t.getSmdpBaseUrl() != null && !t.getSmdpBaseUrl().isBlank();
        return new Binding(own ? t.getSmdpBaseUrl().trim() : defaultBaseUrl,
                t != null && t.getSmdpRequesterId() != null && !t.getSmdpRequesterId().isBlank() ? t.getSmdpRequesterId() : tenantId,
                own ? (t.getSmdpToken() == null ? "" : t.getSmdpToken()) : defaultToken,
                t != null && t.getSmdpAddress() != null && !t.getSmdpAddress().isBlank() ? t.getSmdpAddress() : defaultAddress);
    }

    @Override
    public boolean enabled(String tenantId) {
        return !binding(tenantId).baseUrl().isBlank();
    }

    private RestClient client(Binding b) {
        return clients.computeIfAbsent(b.baseUrl() + "|" + b.token(), k -> {
            RestClient.Builder cb = builder.clone().baseUrl(b.baseUrl())
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("X-Admin-Protocol", "gsma/rsp/v2.2.0");
            if (!b.token().isBlank()) {
                cb.defaultHeader("Authorization", "Bearer " + b.token());
            }
            return cb.build();
        });
    }

    @Override
    public Optional<Profile> order(String tenantId, String eid, String iccid, String profileType) {
        Binding b = binding(tenantId);
        if (b.baseUrl().isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> order = new LinkedHashMap<>(header(b));
            if (eid != null) order.put("eid", eid);
            if (iccid != null) order.put("iccid", iccid);
            if (profileType != null) order.put("profileType", profileType);
            Map<String, Object> ordered = call(b, "downloadOrder", order);
            if (ordered == null) {
                return Optional.empty();
            }
            String allocated = ordered.get("iccid") == null ? iccid : String.valueOf(ordered.get("iccid"));
            Map<String, Object> confirm = new LinkedHashMap<>(header(b));
            confirm.put("iccid", allocated);
            if (eid != null) confirm.put("eid", eid);
            confirm.put("releaseFlag", true);
            Map<String, Object> confirmed = call(b, "confirmOrder", confirm);
            if (confirmed == null || confirmed.get("matchingId") == null) {
                return Optional.empty();
            }
            String address = confirmed.get("smdpAddress") == null ? b.address() : String.valueOf(confirmed.get("smdpAddress"));
            return Optional.of(new Profile(allocated, String.valueOf(confirmed.get("matchingId")), address));
        } catch (RuntimeException e) {
            log.warn("SM-DP+ order failed for tenant {} (eid {}): {}", tenantId, eid, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean cancel(String tenantId, String iccid, String matchingId) {
        Binding b = binding(tenantId);
        if (b.baseUrl().isBlank()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>(header(b));
        body.put("iccid", iccid);
        if (matchingId != null) body.put("matchingId", matchingId);
        body.put("finalProfileStatusIndicator", "Unavailable");
        return call(b, "cancelOrder", body) != null;
    }

    @Override
    public boolean release(String tenantId, String iccid) {
        Binding b = binding(tenantId);
        if (b.baseUrl().isBlank()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>(header(b));
        body.put("iccid", iccid);
        return call(b, "releaseProfile", body) != null;
    }

    private static Map<String, Object> header(Binding b) {
        return Map.of("header", Map.of("functionRequesterIdentifier", b.requesterId(),
                "functionCallIdentifier", UUID.randomUUID().toString()));
    }

    /** One ES2+ call; null when the SM-DP+ did not execute it. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Binding b, String function, Map<String, Object> body) {
        try {
            Map<String, Object> reply = client(b).post().uri(ES2 + function).body(body).retrieve().body(Map.class);
            Object h = reply == null ? null : reply.get("header");
            Object st = h instanceof Map<?, ?> hm && hm.get("functionExecutionStatus") instanceof Map<?, ?> fes ? fes.get("status") : null;
            if (!"Executed-Success".equals(st)) {
                log.warn("SM-DP+ {} refused: {}", function, reply);
                return null;
            }
            return reply;
        } catch (RuntimeException e) {
            log.warn("SM-DP+ {} unreachable: {}", function, e.getMessage());
            return null;
        }
    }
}
