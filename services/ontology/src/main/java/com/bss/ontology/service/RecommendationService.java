package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What should I do next?" — grounded, never free. Reads the customer's context
 * (lines, bills, orders, incidents) and answers with governed ACTIONS of the
 * ontology, each dry-run through the registry's own check so the "why" is the
 * action's verdicts in words, plus things to explain (an incident, an overdue
 * bill) that are reads, not writes. No model call: this is the deterministic
 * half of the desk's Assist; the model only phrases it afterwards.
 */
@Service
public class RecommendationService {

    private final ContextService contextService;
    private final ActionCheckService checks;
    private final Registry registry;
    private final ComponentClient client;

    public RecommendationService(ContextService contextService, ActionCheckService checks, Registry registry, ComponentClient client) {
        this.contextService = contextService;
        this.checks = checks;
        this.registry = registry;
        this.client = client;
    }

    public Map<String, Object> forCustomer(String customerId, Caller caller) {
        Map<String, Object> ctx = contextService.customer(customerId, caller);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> situation = new ArrayList<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());

        // incidents touching this customer's lines (TMF656 service problems, open)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) ctx.getOrDefault("services", List.of());
        List<String> serviceIds = services.stream().map(s -> String.valueOf(s.get("id"))).toList();
        for (JsonNode p : openProblems(caller)) {
            // TMF656 as this platform serves it: affectedObject names the service (or area) the problem sits on
            String affected = p.path("affectedObject").asText("");
            boolean mine = !affected.isEmpty() && serviceIds.stream().anyMatch(id -> affected.equals(id) || affected.contains(id));
            for (JsonNode a : p.path("affectedService")) {
                if (serviceIds.contains(a.path("id").asText())) {
                    mine = true;
                }
            }
            if (mine) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("kind", "incident");
                s.put("id", p.path("id").asText());
                s.put("says", p.path("name").asText(p.path("description").asText("a network incident")) + " affects this customer's line"
                        + (p.path("reason").asText("").isEmpty() ? "" : " — " + p.path("reason").asText()));
                s.put("since", p.path("createdAt").asText(""));
                situation.add(s);
                out.add(explain("explainIncident", "Explain the incident before troubleshooting", s.get("says") + ". Say what is known and when it should be over; do not walk the customer through device checks.", 1));
            }
        }

        // lines: a paused line, a line with headroom to grow
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subs = (List<Map<String, Object>>) ctx.getOrDefault("subscriptions", List.of());
        for (Map<String, Object> s : services) {
            if ("suspended".equalsIgnoreCase(String.valueOf(s.get("state")))) {
                Map<String, Object> sit = new LinkedHashMap<>();
                sit.put("kind", "paused");
                sit.put("id", String.valueOf(s.get("id")));
                sit.put("says", "the line " + s.get("number") + " is paused — nothing is charged and nothing connects until it is resumed");
                situation.add(sit);
                out.add(action(layer, "resumeSubscription", Map.of("serviceId", String.valueOf(s.get("id"))),
                        "the line " + s.get("number") + " is paused", caller, 2));
            }
        }
        for (Map<String, Object> s : subs) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ups = (List<Map<String, Object>>) s.get("availableUpgrades");
            if (ups != null && !ups.isEmpty() && "active".equalsIgnoreCase(String.valueOf(s.get("status")))) {
                Map<String, Object> top = ups.get(0);
                out.add(action(layer, "upgradeSubscription", Map.of("subscriptionId", String.valueOf(s.get("id")), "targetOfferingId", String.valueOf(top.get("id"))),
                        s.get("name") + " could become " + top.get("name") + " (" + top.get("monthly") + " a month)", caller, 4));
            }
        }

        // bills: unpaid past their date, or under dispute
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bills = (List<Map<String, Object>>) ctx.getOrDefault("bills", List.of());
        for (Map<String, Object> b : bills) {
            String state = String.valueOf(b.getOrDefault("state", ""));
            double due;
            try {
                due = Double.parseDouble(String.valueOf(b.getOrDefault("amountDue", "0")));
            } catch (NumberFormatException e) {
                due = 0;
            }
            if (due > 0 && List.of("new", "validated", "sent", "partiallyPaid").contains(state)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("kind", "bill");
                s.put("id", b.get("id"));
                s.put("says", "a bill of " + b.get("amountDue") + " is open (" + state + ")");
                situation.add(s);
                out.add(explain("explainBill", "Walk through the open bill", "A bill of " + b.get("amountDue") + " is " + state + ". Explain the lines before offering anything; a dispute pauses collection while it is looked at.", 3));
                break;
            }
        }

        // receipts: what the BSS already decided about this customer, for the "why"
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) ctx.getOrDefault("receipts", List.of());
        // one recommendation per (action, what): a customer with several identical products is told once
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> rec : out) {
            String key = rec.get("action") + "|" + rec.getOrDefault("why", "");
            unique.putIfAbsent(key, rec);
        }
        out = new ArrayList<>(unique.values());
        out.sort((a, b) -> Integer.compare((int) a.get("priority"), (int) b.get("priority")));
        if (out.size() > 6) {
            out = new ArrayList<>(out.subList(0, 6));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customerId);
        result.put("situation", situation);
        result.put("recommendations", out);
        result.put("receipts", receipts.stream().limit(5).toList());
        result.put("unanswered", ctx.get("unanswered"));
        result.put("said", out.isEmpty() ? "Nothing stands out: no open incident, no paused line, no open bill, no dearer plan in the family. Listen first."
                : "Top recommendation: " + out.get(0).get("title") + " — " + out.get(0).get("why"));
        result.put("@type", "CustomerRecommendations");
        return result;
    }

    private Map<String, Object> action(Registry.Layer layer, String name, Map<String, String> inputs, String because, Caller caller, int priority) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "action");
        m.put("action", name);
        m.put("title", ExplainService.title(name));
        m.put("inputs", inputs);
        m.put("priority", priority);
        JsonNode def = layer.actions().get(name);
        if (def == null) {
            m.put("why", because + " — but the action is not in this tenant's registry");
            m.put("allowed", false);
            return m;
        }
        ActionCheckService.Check c = checks.check(def, inputs, caller);
        m.put("allowed", c.allowed());
        m.put("check", c.toMap());
        m.put("why", because + (c.allowed() ? "; every condition holds for this caller" : "; it cannot happen now: " + c.refusal()));
        m.put("meaning", def.path("meaning").asText());
        m.put("autonomy", def.path("governance").path("autonomy").asText());
        return m;
    }

    private static Map<String, Object> explain(String id, String title, String why, int priority) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "explain");
        m.put("action", id);
        m.put("title", title);
        m.put("why", why);
        m.put("priority", priority);
        m.put("allowed", true);
        return m;
    }

    private List<JsonNode> openProblems(Caller caller) {
        List<JsonNode> out = new ArrayList<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("assurance.problems");
        if (cap == null) {
            return out;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of(), Map.of("limit", "100"), null, caller.bearer(), Map.of());
        if (!reply.ok() || !reply.body().isArray()) {
            return out;
        }
        for (JsonNode p : reply.body()) {
            String status = p.path("status").asText("").toLowerCase();
            if (status.isEmpty() || List.of("submitted", "acknowledged", "inprogress", "in progress", "held", "open").contains(status)) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean isPast(String iso) {
        try {
            return OffsetDateTime.parse(iso).isBefore(OffsetDateTime.now());
        } catch (Exception e) {
            return false;
        }
    }
}
