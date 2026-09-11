package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What could this line become?" — the available upgrades of a subscription:
 * offerings on the caller's channel, in the same family, on sale, not bundles,
 * dearer per month than the current plan. The same list is the receipt's
 * candidate set, so a learning contract on this action has real alternatives to
 * learn from.
 */
@Service
public class UpgradeService {

    private final Registry registry;
    private final Resolver resolver;
    private final ComponentClient client;

    public UpgradeService(Registry registry, Resolver resolver, ComponentClient client) {
        this.registry = registry;
        this.resolver = resolver;
        this.client = client;
    }

    public List<Map<String, Object>> availableUpgrades(String subscriptionId, Caller caller) {
        JsonNode action = registry.forTenant(caller.tenant()).actions().get("upgradeSubscription");
        Resolver.Resolved r = resolver.resolve(action, Map.of("subscriptionId", subscriptionId), caller);
        return availableUpgrades(r, caller);
    }

    public List<Map<String, Object>> availableUpgrades(Resolver.Resolved r, Caller caller) {
        JsonNode current = r.get("currentOffering");
        List<Map<String, Object>> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        String family = current.path("category").path(0).path("name").asText();
        BigDecimal currentMonthly = resolver.monthlyOf("currentOffering", r, caller);
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("productCatalog.offerings");
        // the shelf pages at 100 (a larger limit is a 400): walk it
        List<JsonNode> shelf = new ArrayList<>();
        for (int offset = 0; offset < 1000; offset += 100) {
            ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                    Map.of(), Map.of("limit", "100", "offset", String.valueOf(offset)), null, caller.bearer(),
                    Map.of(Caller.CHANNEL_HEADER, caller.channel()));
            if (!reply.ok() || !reply.body().isArray()) {
                break;
            }
            reply.body().forEach(shelf::add);
            if (reply.body().size() < 100) {
                break;
            }
        }
        JsonNode concept = layer.concepts().get("ProductOffering");
        List<String> live = new ArrayList<>();
        concept.path("states").path("live").forEach(s -> live.add(s.asText().toLowerCase()));
        for (JsonNode o : shelf) {
            if (o.path("id").asText().equals(current.path("id").asText()) || o.path("isBundle").asBoolean(false)) {
                continue;
            }
            if (o.path("requiresVerifiedIdentity").asBoolean(false) || o.path("productOfferingTerm").size() > 0) {
                // a step-up identity or a commitment term is a new contract, not an in-place upgrade
                continue;
            }
            if (!live.contains(o.path("lifecycleStatus").asText("").toLowerCase())) {
                continue;
            }
            if (!family.equalsIgnoreCase(o.path("category").path(0).path("name").asText())) {
                continue;
            }
            String key = "candidate:" + o.path("id").asText();
            r.objects.put(key, o);
            BigDecimal monthly = resolver.monthlyOf(key, r, caller);
            if (monthly == null || (currentMonthly != null && monthly.compareTo(currentMonthly) <= 0)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.path("id").asText());
            row.put("name", o.path("name").asText());
            row.put("monthly", monthly);
            row.put("family", family);
            out.add(row);
        }
        out.sort((a, b) -> ((BigDecimal) a.get("monthly")).compareTo((BigDecimal) b.get("monthly")));
        return out;
    }
}
