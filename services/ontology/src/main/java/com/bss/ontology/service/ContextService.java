package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The context resolver: one call assembles a customer's sub-graph for an agent
 * — the person, their subscriptions with what each could become, their lines,
 * their bills, and the receipts of what the BSS decided about them — walked
 * over the live TM Forum resources with the CALLER's rights. No graph database:
 * the links in the concepts are the graph. Every edge that did not answer says
 * so, so an agent knows what it is not seeing.
 */
@Service
public class ContextService {

    private final Registry registry;
    private final ComponentClient client;
    private final UpgradeService upgrades;
    private final ObjectMapper json;

    public ContextService(Registry registry, ComponentClient client, UpgradeService upgrades, ObjectMapper json) {
        this.registry = registry;
        this.client = client;
        this.upgrades = upgrades;
        this.json = json;
    }

    public Map<String, Object> customer(String customerId, Caller caller) {
        Registry.Layer l = registry.forTenant(caller.tenant());
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> unanswered = new ArrayList<>();
        out.put("customerId", customerId);
        JsonNode person = read(l, "party.individual", Map.of("id", customerId), Map.of(), caller, unanswered, "customer");
        if (person != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", person.path("id").asText(customerId));
            p.put("name", (person.path("givenName").asText("") + " " + person.path("familyName").asText("")).trim());
            p.put("status", person.path("status").asText(""));
            out.put("customer", p);
        }
        List<Map<String, Object>> subs = new ArrayList<>();
        JsonNode products = read(l, "productInventory.products", Map.of(), Map.of("relatedPartyId", customerId, "limit", "100"), caller, unanswered, "subscriptions");
        if (products != null && products.isArray()) {
            for (JsonNode pr : products) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", pr.path("id").asText());
                s.put("name", pr.path("name").asText());
                s.put("status", pr.path("status").asText());
                s.put("offeringId", pr.path("productOffering").path("id").asText());
                s.put("since", pr.path("startDate").asText(""));
                if (pr.has("previousOffering")) {
                    s.put("previousOffering", pr.path("previousOffering").path("name").asText(pr.path("previousOffering").path("id").asText()));
                    s.put("offeringChangedAt", pr.path("offeringChangedAt").asText(""));
                }
                if ("active".equalsIgnoreCase(pr.path("status").asText())) {
                    try {
                        s.put("availableUpgrades", upgrades.availableUpgrades(pr.path("id").asText(), caller));
                    } catch (RuntimeException e) {
                        unanswered.add("availableUpgrades of " + pr.path("id").asText());
                    }
                }
                subs.add(s);
            }
        }
        out.put("subscriptions", subs);
        List<Map<String, Object>> lines = new ArrayList<>();
        JsonNode services = read(l, "serviceInventory.services", Map.of(), Map.of("relatedPartyId", customerId, "limit", "100"), caller, unanswered, "services");
        if (services != null && services.isArray()) {
            for (JsonNode sv : services) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", sv.path("id").asText());
                s.put("name", sv.path("name").asText());
                s.put("state", sv.path("state").asText());
                String number = "";
                for (JsonNode r : sv.path("supportingResource")) {
                    if (!r.path("value").asText("").isEmpty()) {
                        number = r.path("value").asText();
                    }
                }
                s.put("number", number);
                lines.add(s);
            }
        }
        out.put("services", lines);
        List<Map<String, Object>> bills = new ArrayList<>();
        JsonNode billRows = read(l, "billing.bills", Map.of(), Map.of("relatedPartyId", customerId, "limit", "12"), caller, unanswered, "bills");
        if (billRows != null && billRows.isArray()) {
            for (JsonNode b : billRows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.path("id").asText());
                m.put("state", b.path("state").asText());
                m.put("billDate", b.path("billDate").asText(""));
                m.put("amountDue", b.path("amountDue").path("value").asText(b.path("amountDue").asText("")));
                bills.add(m);
            }
        }
        out.put("bills", bills);
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (Map<String, Object> s : subs) {
            JsonNode rows = read(l, "decisionLog.read", Map.of(), Map.of("subjectId", String.valueOf(s.get("id")), "limit", "10"), caller, unanswered, "receipts");
            if (rows != null && rows.isArray()) {
                for (JsonNode d : rows) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("decisionId", d.path("decisionId").asText());
                    m.put("decisionPoint", d.path("decisionPoint").asText());
                    m.put("action", d.path("action").asText());
                    m.put("reason", d.path("reason").asText());
                    m.put("decidedAt", d.path("decidedAt").asText());
                    m.put("outcome", d.path("outcome").asText(""));
                    receipts.add(m);
                }
            }
        }
        out.put("receipts", receipts);
        List<String> actions = new ArrayList<>();
        for (JsonNode a : l.actions().values()) {
            if (!"deprecated".equals(a.path("status").asText())) {
                actions.add(a.path("action").asText() + " — " + a.path("meaning").asText());
            }
        }
        out.put("actionsAvailable", actions);
        out.put("unanswered", unanswered);
        out.put("@type", "CustomerContext");
        return out;
    }

    private JsonNode read(Registry.Layer l, String capability, Map<String, String> pathVars, Map<String, String> query,
            Caller caller, List<String> unanswered, String edge) {
        JsonNode cap = l.capabilities().get(capability);
        if (cap == null || !cap.has("route")) {
            unanswered.add(edge + " (no capability)");
            return null;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), cap.path("route").path("method").asText(),
                cap.path("route").path("path").asText(), pathVars, query, null, caller.bearer(), Map.of());
        if (!reply.ok()) {
            unanswered.add(edge + " (" + Resolver.statusWords(reply) + ")");
            return null;
        }
        return reply.body();
    }
}
