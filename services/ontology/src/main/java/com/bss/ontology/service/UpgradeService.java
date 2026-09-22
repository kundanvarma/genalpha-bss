package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.UpgradeOption;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    public List<UpgradeOption> availableUpgrades(String subscriptionId, Caller caller) {
        JsonNode action = registry.forTenant(caller.tenant()).actions().get("upgradeSubscription");
        Resolver.Resolved r = resolver.resolve(action, Map.of("subscriptionId", subscriptionId), caller);
        return availableUpgrades(r, caller);
    }

    /** The families an in-place upgrade applies to, as the action itself declares them (precondition plan-family). */
    List<String> planFamilies(Caller caller) {
        JsonNode action = registry.forTenant(caller.tenant()).actions().get("upgradeSubscription");
        List<String> out = new ArrayList<>();
        if (action != null) {
            for (JsonNode pc : action.path("preconditions")) {
                if ("plan-family".equals(pc.path("id").asText())) {
                    for (String f : pc.path("args").path(1).asText("").split(",")) {
                        out.add(f.trim());
                    }
                }
            }
        }
        return out;
    }

    public List<UpgradeOption> availableUpgrades(Resolver.Resolved r, Caller caller) {
        JsonNode current = r.get("currentOffering");
        List<UpgradeOption> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        String family = current.path("category").path(0).path("name").asText();
        if (!planFamilies(caller).stream().anyMatch(f -> f.equalsIgnoreCase(family))) {
            return out; // a device, a pass, a top-up: bought, not upgraded in place
        }
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
        // the catalog's own word: exchangableTo names the offerings this one may become; the family rule is the fallback
        List<String> exchangeable = new ArrayList<>();
        for (JsonNode rel : current.path("productOfferingRelationship")) {
            if ("exchangableto".equalsIgnoreCase(rel.path("relationshipType").asText(""))) {
                exchangeable.add(rel.path("id").asText());
            }
        }
        for (JsonNode o : shelf) {
            if (o.path("id").asText().equals(current.path("id").asText()) || o.path("isBundle").asBoolean(false)) {
                continue;
            }
            if (!exchangeable.isEmpty() && !exchangeable.contains(o.path("id").asText())) {
                continue;
            }
            if (o.path("requiresVerifiedIdentity").asBoolean(false) || o.path("productOfferingTerm").size() > 0) {
                // a step-up identity or a commitment term is a new contract, not an in-place upgrade
                continue;
            }
            if (!live.contains(o.path("lifecycleStatus").asText("").toLowerCase())) {
                continue;
            }
            if (exchangeable.isEmpty() && !family.equalsIgnoreCase(o.path("category").path(0).path("name").asText())) {
                continue;
            }
            String key = "candidate:" + o.path("id").asText();
            r.objects.put(key, o);
            BigDecimal monthly = resolver.monthlyOf(key, r, caller);
            if (monthly == null || (currentMonthly != null && monthly.compareTo(currentMonthly) <= 0)) {
                continue;
            }
            out.add(new UpgradeOption(o.path("id").asText(), o.path("name").asText(), monthly, family));
        }
        out.sort((a, b) -> a.monthly().compareTo(b.monthly()));
        return out;
    }
}
