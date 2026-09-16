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
    private final ReceiptPublisher publisher;

    /** What became of a recommendation — the only words the loop accepts. */
    public static final List<String> OUTCOMES = List.of("accepted", "dismissed", "helpful", "unhelpful", "deferred", "rejected");
    /** How long "maybe later" keeps an offer off the customer's Home. */
    static final int DEFER_DAYS = 30;
    static final String DECISION_POINT = "ontology.recommend";

    public RecommendationService(ContextService contextService, ActionCheckService checks, Registry registry, ComponentClient client, ReceiptPublisher publisher) {
        this.contextService = contextService;
        this.checks = checks;
        this.registry = registry;
        this.client = client;
        this.publisher = publisher;
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
                s.put("severity", "warning");
                s.put("actionRequired", false); // the network team's, not the customer's
                situation.add(s);
                out.add(explain("explainIncident", "Explain the incident before troubleshooting", s.get("says") + ". Say what is known and when it should be over; do not walk the customer through device checks.", 1));
            }
        }

        // lines: a paused line, a line with headroom to grow
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subs = (List<Map<String, Object>>) ctx.getOrDefault("subscriptions", List.of());
        for (Map<String, Object> s : services) {
            if ("suspended".equalsIgnoreCase(String.valueOf(s.get("state")))) {
                // a line is named by its number when it has one, else by what it is
                String number = String.valueOf(s.getOrDefault("number", "")).trim();
                String line = number.isEmpty() ? String.valueOf(s.getOrDefault("name", "a line")) : "the line " + number;
                Map<String, Object> sit = new LinkedHashMap<>();
                sit.put("kind", "paused");
                sit.put("id", String.valueOf(s.get("id")));
                sit.put("name", s.get("name"));
                sit.put("says", line + " is paused — nothing is charged and nothing connects until it is resumed");
                sit.put("severity", "warning");
                sit.put("actionRequired", true);
                situation.add(sit);
                out.add(action(layer, "resumeSubscription", Map.of("serviceId", String.valueOf(s.get("id"))),
                        line + " is paused", caller, 2));
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

        // money: the EFFECTIVE state, not the bill flag — the collection case (overdue, a promise to pay
        // that holds the ladder, a dispute or hardship hold) decides what the customer is actually in
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bills = (List<Map<String, Object>>) ctx.getOrDefault("bills", List.of());
        Map<String, Object> open = null;
        for (Map<String, Object> b : bills) {
            String state = String.valueOf(b.getOrDefault("state", ""));
            double due;
            try {
                due = Double.parseDouble(String.valueOf(b.getOrDefault("amountDue", "0")));
            } catch (NumberFormatException e) {
                due = 0;
            }
            if (due > 0 && List.of("new", "validated", "sent", "partiallyPaid").contains(state)) {
                open = b;
                break;
            }
        }
        JsonNode ccase = collectionCase(customerId, caller);
        String caseState = ccase == null ? "" : ccase.path("state").asText("").toLowerCase();
        double overdueNow = ccase == null ? 0 : ccase.path("overdueBalance").path("value").asDouble(0);
        // a case is "open" only while money is actually overdue: a cured case stays on file as "current" with a zero balance
        boolean caseOpen = ccase != null && overdueNow > 0 && !List.of("closed", "settled", "cured", "current", "none", "writtenoff", "written-off").contains(caseState);
        JsonNode promise = ccase == null ? null : ccase.path("holds").path("promiseToPay");
        boolean promised = promise != null && promise.isObject() && !isPast(promise.path("dueAt").asText(""));
        String currency = ccase == null ? "" : ccase.path("overdueBalance").path("unit").asText("");
        if (caseOpen && promised) {
            String amount = promise.path("amount").asText(ccase.path("overdueBalance").path("value").asText(""));
            String by = promise.path("dueAt").asText("").length() >= 10 ? promise.path("dueAt").asText("").substring(0, 10) : "";
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "arranged");
            s.put("id", ccase.path("id").asText());
            s.put("amount", amount);
            s.put("currency", currency);
            s.put("dueAt", promise.path("dueAt").asText(""));
            s.put("says", "a payment plan is agreed — " + amount + (currency.isEmpty() ? "" : " " + currency) + " by " + by + "; nothing else is due until then");
            s.put("severity", "info");
            s.put("actionRequired", false);
            situation.add(s);
            out.add(explain("explainBill", "Confirm the payment plan", "The customer promised " + amount + " by " + by + ". Collection is on hold until then; confirm the date, offer to take the payment early, and offer nothing else.", 3));
        } else if (caseOpen && ccase.path("holds").has("dispute")) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "disputed");
            s.put("id", ccase.path("id").asText());
            s.put("amount", ccase.path("overdueBalance").path("value").asText(""));
            s.put("currency", currency);
            s.put("says", "part of the bill is under dispute — collection waits while it is looked at");
            s.put("severity", "info");
            s.put("actionRequired", false);
            situation.add(s);
            out.add(explain("explainBill", "Walk through the disputed bill", "A dispute is open on this customer's bill; collection waits. Explain what is contested and what happens next.", 3));
        } else if (caseOpen) {
            String amount = ccase.path("overdueBalance").path("value").asText("");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "overdue");
            s.put("id", ccase.path("id").asText());
            s.put("amount", amount);
            s.put("currency", currency);
            s.put("step", ccase.path("stepIndex").asInt(0));
            s.put("says", "an amount of " + amount + (currency.isEmpty() ? "" : " " + currency) + " is overdue — services are at risk until it is settled or a payment plan is agreed");
            s.put("severity", "critical");
            s.put("actionRequired", true);
            situation.add(s);
            out.add(explain("explainBill", "Settle the overdue amount", "An amount of " + amount + " is overdue and the collection ladder is running. Take the payment or agree a promise to pay before anything else.", 1));
        } else if (open != null) {
            String state = String.valueOf(open.getOrDefault("state", ""));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "bill");
            s.put("id", open.get("id"));
            s.put("amount", open.get("amountDue"));
            s.put("says", "a bill of " + open.get("amountDue") + " is open (" + state + ")");
            s.put("severity", "info");
            s.put("actionRequired", false);
            situation.add(s);
            out.add(explain("explainBill", "Walk through the open bill", "A bill of " + open.get("amountDue") + " is " + state + ". Explain the lines before offering anything; a dispute pauses collection while it is looked at.", 3));
        }

        // offers: the recommendation engine's ranked picks become recommendations of the ontology, so they
        // carry a decision id, a "why", and the customer's own verdict (accepted, maybe later, not interested)
        // — but never while something critical is open: no sale before the situation
        String worst = worstSeverity(situation);
        if (!"critical".equals(worst)) {
            java.util.Set<String> suppressed = suppressedOffers(customerId, caller);
            int shown = 0;
            for (JsonNode item : rankedOffers(customerId, caller)) {
                String offeringId = item.path("offering").path("id").asText("");
                if (offeringId.isEmpty() || suppressed.contains(offeringId) || shown >= 3) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "offer");
                m.put("action", "considerOffer");
                m.put("title", item.path("offering").path("name").asText(offeringId));
                m.put("inputs", Map.of("offeringId", offeringId));
                m.put("offeringId", offeringId);
                m.put("priority", 5 + shown);
                m.put("allowed", true);
                String reason = item.path("reason").asText(item.path("description").asText(""));
                m.put("why", reason.isEmpty() ? "picked from what this customer holds and looked at — nothing they already own" : reason);
                m.put("meaning", "an offer to consider; the customer decides, and their verdict is remembered");
                out.add(m);
                shown++;
            }
        }

        // receipts: what the BSS already decided about this customer, for the "why"
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) ctx.getOrDefault("receipts", List.of());
        // one recommendation per (action, what): a customer with several identical products is told once
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> rec : out) {
            String key = rec.get("action") + "|" + rec.getOrDefault("offeringId", "") + "|" + rec.getOrDefault("why", "");
            unique.putIfAbsent(key, rec);
        }
        out = new ArrayList<>(unique.values());
        // the loop: what this desk did with the same recommendation before moves it up or down —
        // the ontology's own decision log is the memory, read with the caller's rights
        Map<String, int[]> history = history(caller);
        for (Map<String, Object> rec : out) {
            int[] h = history.getOrDefault(String.valueOf(rec.get("action")), new int[3]);
            int shown = h[0];
            int good = h[1];
            int bad = h[2];
            int decided = good + bad; // the loop judges on VERDICTS; a recommendation nobody answered teaches nothing
            int adjustment = 0;
            String says;
            if (decided < 5) {
                says = shown == 0 ? "no history on this desk yet — ranked by the situation alone"
                        : "too few verdicts yet (" + shown + " shown, " + decided + " answered) — ranked by the situation alone";
            } else {
                double rate = (good - bad) / (double) decided;
                adjustment = rate <= -0.5 ? 2 : rate <= -0.2 ? 1 : rate >= 0.5 ? -1 : 0;
                says = "shown " + shown + " times on this desk; taken or found helpful " + good + ", dismissed or found unhelpful " + bad
                        + (adjustment > 0 ? " — ranked down" : adjustment < 0 ? " — ranked up" : " — rank unchanged");
            }
            Map<String, Object> ranking = new LinkedHashMap<>();
            ranking.put("priority", rec.get("priority"));
            ranking.put("shown", shown);
            ranking.put("accepted", good);
            ranking.put("dismissed", bad);
            ranking.put("adjustment", adjustment);
            ranking.put("says", says);
            rec.put("ranking", ranking);
            rec.put("rank", (int) rec.get("priority") + adjustment);
        }
        out.sort((a, b) -> Integer.compare((int) a.get("rank"), (int) b.get("rank")));
        // six things to do at most; offers ride along unclipped (there are never more than three)
        List<Map<String, Object>> todo = out.stream().filter(r -> !"offer".equals(r.get("kind"))).toList();
        List<Map<String, Object>> offers = out.stream().filter(r -> "offer".equals(r.get("kind"))).toList();
        if (todo.size() > 6) {
            todo = todo.subList(0, 6);
        }
        out = new ArrayList<>(todo);
        out.addAll(offers);
        // every recommendation shown is a decision of the BSS, so its outcome can be learned from
        List<String> candidates = out.stream().map(r -> String.valueOf(r.get("action"))).toList();
        List<String> eligible = out.stream().filter(r -> Boolean.TRUE.equals(r.get("allowed"))).map(r -> String.valueOf(r.get("action"))).toList();
        for (Map<String, Object> rec : out) {
            String decisionId = "rec-" + java.util.UUID.randomUUID();
            rec.put("decisionId", decisionId);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("decisionId", decisionId);
            d.put("decisionPoint", DECISION_POINT);
            d.put("subjectType", "customer");
            d.put("subjectId", customerId);
            d.put("candidates", candidates);
            d.put("eligibleActions", eligible);
            d.put("constraints", List.of("dry-run through the registry before it is shown", "the agent decides; the desk only recommends"));
            d.put("action", rec.get("action"));
            d.put("propensity", null);
            d.put("policy", "assist-ranking");
            d.put("policyVersion", "1");
            d.put("reason", rec.get("why"));
            Map<String, Object> dctx = new LinkedHashMap<>();
            dctx.put("kind", rec.get("kind"));
            dctx.put("rank", rec.get("rank"));
            dctx.put("ranking", rec.get("ranking"));
            dctx.put("agent", ActionExecuteService.agentLabel(registry, caller));
            if (rec.get("offeringId") != null) {
                dctx.put("offeringId", rec.get("offeringId"));
            }
            d.put("context", dctx);
            d.put("evidence", Map.of("situation", situation.stream().map(x -> String.valueOf(x.get("says"))).toList()));
            d.put("autonomy", "assist");
            d.put("fallback", false);
            d.put("source", "ontology");
            d.put("contract", null);
            d.put("decidedAt", OffsetDateTime.now().toString());
            d.put("@type", "Decision");
            publisher.decision(caller.tenant(), d);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customerId);
        result.put("situation", situation);
        // the situation SUMMARISED: one entry per kind with a severity, whether the customer must act,
        // and how many lines it covers — what a Home or a desk shows, instead of one row per event
        result.put("summary", summarize(situation));
        result.put("severity", worstSeverity(situation));
        result.put("healthy", situation.stream().noneMatch(s -> !"info".equals(String.valueOf(s.get("severity")))));
        result.put("recommendations", out);
        result.put("receipts", receipts.stream().limit(5).toList());
        result.put("unanswered", ctx.get("unanswered"));
        result.put("said", out.isEmpty() ? "Nothing stands out: no open incident, no paused line, no open bill, no dearer plan in the family. Listen first."
                : "Top recommendation: " + out.get(0).get("title") + " — " + out.get(0).get("why"));
        result.put("@type", "CustomerRecommendations");
        return result;
    }

    /** The agent's verdict on a recommendation, back into the decision log where the ranking reads it. */
    public Map<String, Object> outcome(String decisionId, String outcome, String reason, Caller caller) {
        if (decisionId == null || !decisionId.startsWith("rec-")) {
            throw new IllegalArgumentException("not a recommendation decision id: " + decisionId);
        }
        if (outcome == null || !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("outcome must be one of " + OUTCOMES);
        }
        publisher.outcome(caller.tenant(), decisionId, outcome, reason == null || reason.isBlank() ? null : reason);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", decisionId);
        m.put("outcome", outcome);
        m.put("said", "Noted: the recommendation was " + outcome + (reason == null || reason.isBlank() ? "" : " (" + reason + ")") + ". The ranking on this desk learns from it.");
        m.put("@type", "RecommendationOutcome");
        return m;
    }

    /** action → {shown, taken-or-helpful, dismissed-or-unhelpful} from this tenant's recent recommendation decisions. */
    private Map<String, int[]> history(Caller caller) {
        Map<String, int[]> out = new LinkedHashMap<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("decisionLog.read");
        if (cap == null) {
            return out;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of(), Map.of("decisionPoint", DECISION_POINT, "limit", "500"), null, caller.bearer(), Map.of());
        if (!reply.ok() || !reply.body().isArray()) {
            return out;
        }
        for (JsonNode d : reply.body()) {
            String action = d.path("action").asText("");
            if (action.isEmpty()) {
                continue;
            }
            int[] h = out.computeIfAbsent(action, k -> new int[3]);
            h[0]++;
            String o = d.path("outcome").asText("");
            if ("accepted".equals(o) || "helpful".equals(o)) {
                h[1]++;
            } else if ("dismissed".equals(o) || "unhelpful".equals(o) || "rejected".equals(o)) {
                h[2]++;
            }
        }
        return out;
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
        // a customer may not read the network's problem list, but may be told about an incident on their OWN
        // line: the registry reads with its own account and the caller only ever sees problems matched to
        // their services (the match happens in forCustomer, on the customer's own service ids)
        ComponentClient.Reply reply = caller.isCustomer()
                ? client.callAsMachine(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                        Map.of("limit", "100"), null, Map.of())
                : client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
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

    /** The customer's collection case, read with the caller's rights: a customer sees their own, staff filter by account. */
    private JsonNode collectionCase(String customerId, Caller caller) {
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("billing.collectionCases");
        if (cap == null) {
            return null;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of(), Map.of(), null, caller.bearer(), Map.of());
        if (!reply.ok() || !reply.body().isArray()) {
            return null;
        }
        for (JsonNode c : reply.body()) {
            if (caller.isCustomer() || customerId.equals(c.path("accountId").asText(""))) {
                return c;
            }
        }
        return null;
    }

    /** The recommendation engine's ranked items for this customer, as the caller may read them. */
    private List<JsonNode> rankedOffers(String customerId, Caller caller) {
        List<JsonNode> out = new ArrayList<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("recommendation.list");
        if (cap == null) {
            return out;
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of(), caller.isCustomer() ? Map.of() : Map.of("relatedPartyId", customerId), null, caller.bearer(), Map.of());
        if (!reply.ok()) {
            return out;
        }
        JsonNode body = reply.body();
        Iterable<JsonNode> recs = body.isArray() ? body : List.of(body);
        for (JsonNode r : recs) {
            for (JsonNode item : r.path("recommendationItem")) {
                out.add(item);
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /** Offers this customer said no to, or "maybe later" within the defer window — read from the decision log with
     * the registry's own account, because the customer may not read the log but their own verdicts are theirs. */
    private java.util.Set<String> suppressedOffers(String customerId, Caller caller) {
        java.util.Set<String> out = new java.util.HashSet<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("decisionLog.read");
        if (cap == null) {
            return out;
        }
        Map<String, String> query = Map.of("decisionPoint", DECISION_POINT, "subjectId", customerId, "limit", "200");
        ComponentClient.Reply reply = caller.isCustomer()
                ? client.callAsMachine(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(), query, null, Map.of())
                : client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(), Map.of(), query, null, caller.bearer(), Map.of());
        if (!reply.ok() || !reply.body().isArray()) {
            return out;
        }
        OffsetDateTime deferHorizon = OffsetDateTime.now().minusDays(DEFER_DAYS);
        for (JsonNode d : reply.body()) {
            String offeringId = d.path("context").path("offeringId").asText("");
            String o = d.path("outcome").asText("");
            if (offeringId.isEmpty()) {
                continue;
            }
            if ("rejected".equals(o)) {
                out.add(offeringId);
            } else if ("deferred".equals(o)) {
                try {
                    String at = d.path("outcomeAt").asText(d.path("decidedAt").asText(""));
                    if (at.isEmpty() || OffsetDateTime.parse(at).isAfter(deferHorizon)) {
                        out.add(offeringId);
                    }
                } catch (Exception e) {
                    out.add(offeringId);
                }
            }
        }
        return out;
    }

    static String worstSeverity(List<Map<String, Object>> situation) {
        String worst = "info";
        for (Map<String, Object> s : situation) {
            String sev = String.valueOf(s.getOrDefault("severity", "info"));
            if ("critical".equals(sev)) {
                return "critical";
            }
            if ("warning".equals(sev)) {
                worst = "warning";
            }
        }
        return situation.isEmpty() ? "none" : worst;
    }

    /** One entry per kind: severity, whether the customer must act, the count, the member ids, and words that
     * fit the count ("5 services are paused", not five rows). Ordered critical → warning → info. */
    static List<Map<String, Object>> summarize(List<Map<String, Object>> situation) {
        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        for (Map<String, Object> s : situation) {
            byKind.computeIfAbsent(String.valueOf(s.get("kind")), k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byKind.entrySet()) {
            List<Map<String, Object>> members = e.getValue();
            Map<String, Object> first = members.get(0);
            int n = members.size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.getKey());
            m.put("severity", first.getOrDefault("severity", "info"));
            m.put("actionRequired", Boolean.TRUE.equals(first.get("actionRequired")));
            m.put("count", n);
            m.put("members", members.stream().map(s -> String.valueOf(s.get("id"))).toList());
            switch (e.getKey()) {
                case "paused" -> m.put("says", n == 1 ? first.get("says")
                        : n + " services are paused — nothing is charged and nothing connects until they are resumed");
                case "incident" -> m.put("says", n == 1 ? first.get("says")
                        : n + " network incidents affect this customer's lines — the network team is on them; nothing to do");
                default -> m.put("says", first.get("says"));
            }
            for (String k : List.of("amount", "currency", "dueAt", "name")) {
                if (first.get(k) != null) {
                    m.put(k, first.get(k));
                }
            }
            out.add(m);
        }
        List<String> order = List.of("critical", "warning", "info");
        out.sort((a, b) -> Integer.compare(order.indexOf(String.valueOf(a.get("severity"))), order.indexOf(String.valueOf(b.get("severity")))));
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
