package com.bss.usage.client;

import com.bss.usage.dto.OcsSubscriber;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generic {@code http} OCS adapter: the subscriber/bucket REST shape the
 * bundled mock-ocs exposes in dev and a vendor's integration gateway exposes
 * in production. Fail-open: no balances beats no page.
 */
@Component
public class HttpOcsClient implements OcsBalanceAdapter {

    private final RestClient.Builder builder;
    private final OcsSettings settings;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public HttpOcsClient(RestClient.Builder builder, OcsSettings settings) {
        this.builder = builder;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public boolean enabled(String tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    private RestClient client(String tenantId) {
        OcsSettings.Binding b = settings.forTenant(tenantId);
        if (!b.enabled()) {
            return null;
        }
        return clients.computeIfAbsent(b.baseUrl(), url -> builder.clone().baseUrl(url).build());
    }

    private static final ParameterizedTypeReference<List<OcsSubscriber>> SUBSCRIBERS =
            new ParameterizedTypeReference<>() { };

    @Override
    public List<OcsSubscriber> subscribersOf(String tenantId, String partyId) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
            return List.of();
        }
        try {
            List<OcsSubscriber> subs = restClient.get()
                    .uri("/subscribers?tenantId={t}&partyId={p}", tenantId, partyId)
                    .retrieve().body(SUBSCRIBERS);
            return subs == null ? List.of() : subs;
        } catch (RuntimeException e) {
            return List.of(); // fail open: no balances beats no page
        }
    }

    @Override
    public boolean credit(String tenantId, String subscriberId, double gb) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
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
