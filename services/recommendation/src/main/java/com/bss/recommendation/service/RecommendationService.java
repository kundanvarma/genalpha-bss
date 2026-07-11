package com.bss.recommendation.service;

import com.bss.recommendation.client.CommerceClients;
import com.bss.recommendation.exception.BadRequestException;
import com.bss.recommendation.security.PartyScope;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF680: what should this customer see next? The v1 recommender is a
 * transparent rule engine — active, sellable offerings the party does not
 * already own, bundles first (they carry the household). The shape leaves
 * room for a learned ranker later without changing the API: channels just
 * render items in priority order.
 */
@Service
public class RecommendationService {

    private final CommerceClients.CatalogClient catalog;
    private final CommerceClients.InventoryClient inventory;
    private final PartyScope partyScope;

    public RecommendationService(CommerceClients.CatalogClient catalog,
            CommerceClients.InventoryClient inventory, PartyScope partyScope) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.partyScope = partyScope;
    }

    public Map<String, Object> recommendationFor(String requestedPartyId) {
        String party = partyScope.scopedPartyId().orElse(requestedPartyId);
        if (party == null) {
            throw new BadRequestException("relatedPartyId is required for unscoped callers");
        }
        Set<String> owned = new HashSet<>();
        for (Map<String, Object> product : inventory.productsOf(party)) {
            if (product.get("productOffering") instanceof Map<?, ?> ref && ref.get("id") != null) {
                owned.add(String.valueOf(ref.get("id")));
            }
        }
        List<Map<String, Object>> candidates = catalog.activeOfferings().stream()
                .filter(o -> !owned.contains(String.valueOf(o.get("id"))))
                .filter(o -> !Boolean.FALSE.equals(o.get("isSellable")))
                .sorted((a, b) -> Boolean.compare(
                        !Boolean.TRUE.equals(a.get("isBundle")),
                        !Boolean.TRUE.equals(b.get("isBundle"))))
                .limit(5)
                .toList();

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> offering = candidates.get(i);
            items.add(Map.of(
                    "priority", i + 1,
                    "offering", Map.of(
                            "id", offering.get("id"),
                            "name", offering.get("name"),
                            "@referredType", "ProductOffering")));
        }
        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("id", UUID.randomUUID().toString());
        recommendation.put("name", "Recommended for you");
        recommendation.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
        recommendation.put("recommendationItem", items);
        recommendation.put("@type", "Recommendation");
        return recommendation;
    }
}
