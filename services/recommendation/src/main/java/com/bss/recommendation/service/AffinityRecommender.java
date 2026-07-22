package com.bss.recommendation.service;

import com.bss.recommendation.client.CommerceClients;
import com.bss.recommendation.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "CUSTOMERS WHO BOUGHT THIS ALSO BOUGHT" — item-to-item market-basket
 * affinity, the iPhone→case signal. Where the personalized rail answers
 * "what should THIS customer see", affinity answers "what goes WITH this
 * product", from what the tenant's customers actually own together.
 *
 * It reveals only aggregates and enforces a minimum support: an offering
 * must be co-owned by at least N of a product's owners to show. That is
 * signal over noise AND a privacy floor — one customer's basket can never
 * be read back from the page. Baskets are cached per tenant (the
 * popularity ranker's pattern), so a browsing session is one inventory
 * scan, not one per product page.
 */
@Component
public class AffinityRecommender {

    private record Baskets(Instant takenAt, Map<String, Set<String>> byOwner) {
    }

    private final CommerceClients.InventoryClient inventory;
    private final CommerceClients.CatalogClient catalog;
    private final TenantScope tenantScope;
    private final int minSupport;
    private final int limit;
    private final long ttlSeconds;
    private final Map<String, Baskets> cache = new ConcurrentHashMap<>();

    public AffinityRecommender(CommerceClients.InventoryClient inventory,
            CommerceClients.CatalogClient catalog, TenantScope tenantScope,
            @Value("${bss.recommendation.affinity-min-support:2}") int minSupport,
            @Value("${bss.recommendation.affinity-limit:4}") int limit,
            @Value("${bss.recommendation.affinity-ttl-seconds:60}") long ttlSeconds) {
        this.inventory = inventory;
        this.catalog = catalog;
        this.tenantScope = tenantScope;
        this.minSupport = minSupport;
        this.limit = limit;
        this.ttlSeconds = ttlSeconds;
    }

    /** The offerings co-owned with X, most co-owned first, min-support
     * filtered — the "also bought" rail. */
    public List<Map<String, Object>> alsoBought(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) {
            return List.of();
        }
        Map<String, Set<String>> baskets = baskets();
        Map<String, Integer> tally = new HashMap<>();
        for (Set<String> basket : baskets.values()) {
            if (!basket.contains(offeringId)) {
                continue; // not one of X's owners
            }
            for (String other : basket) {
                if (!other.equals(offeringId)) {
                    tally.merge(other, 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> ranked = tally.entrySet().stream()
                .filter(e -> e.getValue() >= minSupport)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(limit)
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = offeringNames();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("offering", Map.of("id", e.getKey(),
                    "name", names.getOrDefault(e.getKey(), e.getKey())));
            row.put("coOwners", e.getValue());
            out.add(row);
        }
        return out;
    }

    /** owner party id -> the set of offering ids they own, cached. */
    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> baskets() {
        String tenant = tenantScope.currentTenantId();
        Baskets cached = cache.get(tenant);
        if (cached != null && cached.takenAt().plusSeconds(ttlSeconds).isAfter(Instant.now())) {
            return cached.byOwner();
        }
        Map<String, Set<String>> byOwner = new HashMap<>();
        try {
            for (Map<String, Object> product : inventory.allProducts()) {
                String owner = ownerOf(product);
                String offering = product.get("productOffering") instanceof Map<?, ?> ref
                        && ref.get("id") != null ? String.valueOf(ref.get("id")) : null;
                if (owner != null && offering != null) {
                    byOwner.computeIfAbsent(owner, k -> new HashSet<>()).add(offering);
                }
            }
        } catch (RuntimeException inventoryDown) {
            return Map.of(); // no data, no affinity — the page stands
        }
        cache.put(tenant, new Baskets(Instant.now(), byOwner));
        return byOwner;
    }

    @SuppressWarnings("unchecked")
    private String ownerOf(Map<String, Object> product) {
        if (!(product.get("relatedParty") instanceof List<?> parties)) {
            return null;
        }
        for (Object p : parties) {
            if (p instanceof Map<?, ?> party
                    && "customer".equalsIgnoreCase(String.valueOf(party.get("role")))
                    && party.get("id") != null) {
                return String.valueOf(party.get("id"));
            }
        }
        return null;
    }

    private Map<String, String> offeringNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (Map<String, Object> o : catalog.activeOfferings()) {
                if (o.get("id") != null) {
                    names.put(String.valueOf(o.get("id")), String.valueOf(o.get("name")));
                }
            }
        } catch (RuntimeException ignored) {
            // names are a nicety; ids still resolve on the storefront
        }
        return names;
    }
}
