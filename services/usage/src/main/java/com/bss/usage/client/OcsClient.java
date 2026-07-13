package com.bss.usage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The usage component's read/credit window onto the Online Charging System.
 * The OCS owns the real-time truth (counters, rollover); this client
 * PROJECTS it for the TMF654 facade and forwards top-up credits. Blank
 * base-url = no OCS in this deployment: balances answer empty and the
 * facade says so honestly.
 */
@Component
public class OcsClient {

    private final RestClient restClient;
    private final boolean enabled;

    public OcsClient(RestClient.Builder builder,
            @Value("${bss.downstream.ocs-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).build() : null;
    }

    public boolean enabled() {
        return enabled;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> subscribersOf(String tenantId, String partyId) {
        if (!enabled) {
            return List.of();
        }
        try {
            List<Map<String, Object>> subs = restClient.get()
                    .uri("/subscribers?tenantId={t}&partyId={p}", tenantId, partyId)
                    .retrieve().body(List.class);
            return subs == null ? List.of() : subs;
        } catch (RuntimeException e) {
            return List.of(); // fail open: no balances beats no page
        }
    }

    public boolean credit(String subscriberId, double gb) {
        if (!enabled) {
            return false;
        }
        try {
            restClient.post().uri("/subscribers/{id}/credit", subscriberId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("gb", gb))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
