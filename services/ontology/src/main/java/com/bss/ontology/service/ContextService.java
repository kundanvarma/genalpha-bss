package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.CustomerContext;
import com.bss.ontology.dto.UpgradeOption;
import com.bss.ontology.registry.Registry;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public ContextService(Registry registry, ComponentClient client, UpgradeService upgrades) {
        this.registry = registry;
        this.client = client;
        this.upgrades = upgrades;
    }

    public CustomerContext customer(String customerId, Caller caller) {
        Registry.Layer l = registry.forTenant(caller.tenant());
        List<String> unanswered = new ArrayList<>();
        CustomerContext.Customer customer = null;
        JsonNode person = read(l, "party.individual", Map.of("id", customerId), Map.of(), caller, unanswered, "customer");
        if (person != null) {
            customer = new CustomerContext.Customer(person.path("id").asString(customerId),
                    (person.path("givenName").asString("") + " " + person.path("familyName").asString("")).trim(),
                    person.path("status").asString(""));
        }
        List<CustomerContext.Subscription> subs = new ArrayList<>();
        JsonNode products = read(l, "productInventory.products", Map.of(), Map.of("relatedPartyId", customerId, "limit", "100"), caller, unanswered, "subscriptions");
        if (products != null && products.isArray()) {
            for (JsonNode pr : products) {
                String previousOffering = null;
                String offeringChangedAt = null;
                if (pr.has("previousOffering")) {
                    previousOffering = pr.path("previousOffering").path("name").asString(pr.path("previousOffering").path("id").asString());
                    offeringChangedAt = pr.path("offeringChangedAt").asString("");
                }
                List<UpgradeOption> availableUpgrades = null;
                if ("active".equalsIgnoreCase(pr.path("status").asString())) {
                    try {
                        availableUpgrades = upgrades.availableUpgrades(pr.path("id").asString(), caller);
                    } catch (RuntimeException e) {
                        unanswered.add("availableUpgrades of " + pr.path("id").asString());
                    }
                }
                subs.add(new CustomerContext.Subscription(pr.path("id").asString(), pr.path("name").asString(), pr.path("status").asString(),
                        pr.path("productOffering").path("id").asString(), pr.path("startDate").asString(""), previousOffering, offeringChangedAt,
                        availableUpgrades));
            }
        }
        List<CustomerContext.ServiceLine> lines = new ArrayList<>();
        JsonNode services = read(l, "serviceInventory.services", Map.of(), Map.of("relatedPartyId", customerId, "limit", "100"), caller, unanswered, "services");
        if (services != null && services.isArray()) {
            for (JsonNode sv : services) {
                String number = "";
                for (JsonNode r : sv.path("supportingResource")) {
                    if (!r.path("value").asString("").isEmpty()) {
                        number = r.path("value").asString();
                    }
                }
                lines.add(new CustomerContext.ServiceLine(sv.path("id").asString(), sv.path("name").asString(), sv.path("state").asString(), number));
            }
        }
        List<CustomerContext.Bill> bills = new ArrayList<>();
        JsonNode billRows = read(l, "billing.bills", Map.of(), Map.of("relatedPartyId", customerId, "limit", "12"), caller, unanswered, "bills");
        if (billRows != null && billRows.isArray()) {
            for (JsonNode b : billRows) {
                bills.add(new CustomerContext.Bill(b.path("id").asString(), b.path("state").asString(), b.path("billDate").asString(""),
                        b.path("amountDue").path("value").asString(b.path("amountDue").asString(""))));
            }
        }
        List<CustomerContext.Receipt> receipts = new ArrayList<>();
        for (CustomerContext.Subscription s : subs) {
            JsonNode rows = read(l, "decisionLog.read", Map.of(), Map.of("subjectId", s.id(), "limit", "10"), caller, unanswered, "receipts");
            if (rows != null && rows.isArray()) {
                for (JsonNode d : rows) {
                    receipts.add(new CustomerContext.Receipt(d.path("decisionId").asString(), d.path("decisionPoint").asString(),
                            d.path("action").asString(), d.path("reason").asString(), d.path("decidedAt").asString(), d.path("outcome").asString("")));
                }
            }
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode a : l.actions().values()) {
            if (!"deprecated".equals(a.path("status").asString())) {
                actions.add(a.path("action").asString() + " — " + a.path("meaning").asString());
            }
        }
        return new CustomerContext(customerId, customer, subs, lines, bills, receipts, actions,
                new ArrayList<>(new java.util.LinkedHashSet<>(unanswered)), "CustomerContext");
    }

    private JsonNode read(Registry.Layer l, String capability, Map<String, String> pathVars, Map<String, String> query,
            Caller caller, List<String> unanswered, String edge) {
        JsonNode cap = l.capabilities().get(capability);
        if (cap == null || !cap.has("route")) {
            unanswered.add(edge + " (no capability)");
            return null;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asString(), cap.path("route").path("method").asString(),
                cap.path("route").path("path").asString(), pathVars, query, null, caller.bearer(), Map.of());
        if (!reply.ok()) {
            unanswered.add(edge + " (" + Resolver.statusWords(reply) + ")");
            return null;
        }
        return reply.body();
    }
}
