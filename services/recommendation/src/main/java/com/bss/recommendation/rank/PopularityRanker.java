package com.bss.recommendation.rank;

import com.bss.recommendation.client.CommerceClients;
import com.bss.recommendation.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The first data-driven ranker: what this tenant's customers actually adopt.
 * Popularity is learned from the live product inventory (owners per
 * offering), refreshed per tenant on a short TTL. Bundles keep their
 * domain-prior head start; within each group the crowd decides. A trained
 * model replaces this class behind the same seam when an operator has the
 * data to feed one.
 */
@Component
@ConditionalOnProperty(name = "bss.recommendation.ranker", havingValue = "popularity",
        matchIfMissing = true)
public class PopularityRanker implements Ranker {

    private record Snapshot(Instant takenAt, Map<String, Long> owners) {
    }

    private final CommerceClients.InventoryClient inventory;
    private final TenantScope tenantScope;
    private final long ttlSeconds;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public PopularityRanker(CommerceClients.InventoryClient inventory, TenantScope tenantScope,
            @Value("${bss.recommendation.popularity-ttl-seconds:60}") long ttlSeconds) {
        this.inventory = inventory;
        this.tenantScope = tenantScope;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public List<Map<String, Object>> rank(List<Map<String, Object>> candidates) {
        Map<String, Long> owners = ownersByOffering();
        return candidates.stream()
                .sorted(Comparator
                        .comparing((Map<String, Object> o) -> !Boolean.TRUE.equals(o.get("isBundle")))
                        .thenComparing(o -> -owners.getOrDefault(String.valueOf(o.get("id")), 0L)))
                .toList();
    }

    private Map<String, Long> ownersByOffering() {
        String tenant = tenantScope.currentTenantId();
        Snapshot cached = cache.get(tenant);
        if (cached != null && cached.takenAt().plusSeconds(ttlSeconds).isAfter(Instant.now())) {
            return cached.owners();
        }
        Map<String, Long> owners = new HashMap<>();
        try {
            for (Map<String, Object> product : inventory.allProducts()) {
                if (product.get("productOffering") instanceof Map<?, ?> ref && ref.get("id") != null) {
                    owners.merge(String.valueOf(ref.get("id")), 1L, Long::sum);
                }
            }
        } catch (Exception e) {
            // Inventory hiccups must not break recommendations; unranked is fine.
            return cached != null ? cached.owners() : Map.of();
        }
        cache.put(tenant, new Snapshot(Instant.now(), owners));
        return owners;
    }
}
