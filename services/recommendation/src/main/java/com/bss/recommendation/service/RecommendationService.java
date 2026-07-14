package com.bss.recommendation.service;

import com.bss.recommendation.client.CommerceClients;
import com.bss.recommendation.exception.BadRequestException;
import com.bss.recommendation.rank.Ranker;
import com.bss.recommendation.security.PartyScope;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF680: what should this customer see next? Candidate selection is a
 * transparent rule (active, sellable, not already owned); the ORDER comes
 * from the Ranker seam — rules or the popularity baseline today, a trained
 * model tomorrow — and the API never changes: channels just render items
 * in priority order.
 */
@Service
public class RecommendationService {

    private final CommerceClients.CatalogClient catalog;
    private final CommerceClients.InventoryClient inventory;
    private final CommerceClients.InsightClient insight;
    private final PartyScope partyScope;
    private final Ranker ranker;

    public RecommendationService(CommerceClients.CatalogClient catalog,
            CommerceClients.InventoryClient inventory, PartyScope partyScope, Ranker ranker,
            CommerceClients.InsightClient insight) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.insight = insight;
        this.partyScope = partyScope;
        this.ranker = ranker;
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
        // FUSION: the customer's consented browsing interests (from the
        // insight component) boost matching categories to the front — a
        // stable re-sort, so the base ranking still decides within groups.
        // Fail-open: no consent or no insight means popularity stands alone.
        List<String> interests = insight.interestsOf(party);
        java.util.function.Predicate<Map<String, Object>> interesting = (o) ->
                o.get("category") instanceof List<?> cats && cats.stream()
                        .anyMatch(c -> c instanceof Map<?, ?> m
                                && interests.contains(String.valueOf(m.get("name"))));
        List<Map<String, Object>> candidates = ranker.rank(catalog.activeOfferings().stream()
                .filter(o -> !owned.contains(String.valueOf(o.get("id"))))
                .filter(o -> !Boolean.FALSE.equals(o.get("isSellable")))
                .toList())
                .stream()
                .sorted((a, b) -> Boolean.compare(interesting.test(b), interesting.test(a)))
                .limit(5).toList();

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
