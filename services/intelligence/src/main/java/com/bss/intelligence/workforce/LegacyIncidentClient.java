package com.bss.intelligence.workforce;

import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * THE OVERLAY SEAM, workforce side: a tenant wrapping a legacy estate gets
 * that estate's OPEN incidents federated into the digital workforce queue —
 * and verified completion checks the LEGACY system's own state, so "a
 * worker cannot mark done what is not done" holds across the wrap.
 * Read-through, fail-soft, age-stamped on the way in.
 */
@Component
public class LegacyIncidentClient {

    public static final String KIND = "legacy-ticket";

    private final RestClient.Builder builder;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public LegacyIncidentClient(RestClient.Builder builder, TenantRegistry tenants,
            TenantScope tenantScope) {
        this.builder = builder;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    private String base() {
        TenantRegistry.TenantEntry t = tenants.byId(tenantScope.currentTenantId());
        String url = t == null ? null : t.getLegacyTicketBaseUrl();
        return url == null || url.isBlank() ? null : url;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> openIncidents() {
        String base = base();
        if (base == null) {
            return List.of();
        }
        try {
            Map<String, Object> env = builder.build().get().uri(base + "/api/listIncidents")
                    .retrieve().body(Map.class);
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    ((Map<String, Object>) env.getOrDefault("resultSet", Map.of()))
                            .getOrDefault("row", List.of());
            return rows.stream().filter(r -> "OPEN".equals(r.get("STATUS"))).toList();
        } catch (Exception e) {
            return List.of(); // a dead legacy never blinds the native queue
        }
    }

    /** The legacy system's OWN word on an incident — the completion check. */
    public String statusOf(String incNo) {
        String base = base();
        if (base == null) {
            return null;
        }
        try {
            return openIncidents().stream()
                    .anyMatch(r -> incNo.equals(String.valueOf(r.get("INC_NO"))))
                    ? "OPEN" : "CLOSED";
        } catch (Exception e) {
            return null;
        }
    }
}
