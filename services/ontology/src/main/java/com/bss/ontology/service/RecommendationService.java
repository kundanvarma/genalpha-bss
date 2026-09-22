package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.CustomerContext;
import com.bss.ontology.dto.CustomerRecommendations;
import com.bss.ontology.dto.Recommendation;
import com.bss.ontology.dto.RecommendationOutcome;
import com.bss.ontology.dto.Situation;
import com.bss.ontology.dto.SituationSummary;
import com.bss.ontology.dto.UpgradeOption;
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

    public CustomerRecommendations forCustomer(String customerId, Caller caller) {
        CustomerContext ctx = contextService.customer(customerId, caller);
        List<Recommendation> out = new ArrayList<>();
        List<Situation> situation = new ArrayList<>();
        Registry.Layer layer = registry.forTenant(caller.tenant());

        // incidents touching this customer's lines (TMF656 service problems, open)
        List<CustomerContext.ServiceLine> services = ctx.services();
        List<String> serviceIds = services.stream().map(CustomerContext.ServiceLine::id).toList();
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
                String says = p.path("name").asText(p.path("description").asText("a network incident")) + " affects this customer's line"
                        + (p.path("reason").asText("").isEmpty() ? "" : " — " + p.path("reason").asText());
                situation.add(Situation.incident(p.path("id").asText(), says, p.path("createdAt").asText(""))); // the network team's, not the customer's
                out.add(Recommendation.explain("explainIncident", "Explain the incident before troubleshooting", says + ". Say what is known and when it should be over; do not walk the customer through device checks.", 1));
            }
        }

        // lines: a paused line, a line with headroom to grow
        for (CustomerContext.ServiceLine s : services) {
            if ("suspended".equalsIgnoreCase(s.state())) {
                // a line is named by its number when it has one, else by what it is
                String number = s.number() == null ? "" : s.number().trim();
                String line = number.isEmpty() ? (s.name() == null ? "a line" : s.name()) : "the line " + number;
                situation.add(Situation.paused(s.id(), s.name(), line + " is paused — nothing is charged and nothing connects until it is resumed"));
                out.add(action(layer, "resumeSubscription", Map.of("serviceId", s.id()), line + " is paused", caller, 2));
            }
        }
        for (CustomerContext.Subscription s : ctx.subscriptions()) {
            List<UpgradeOption> ups = s.availableUpgrades();
            if (ups != null && !ups.isEmpty() && "active".equalsIgnoreCase(s.status())) {
                UpgradeOption top = ups.get(0);
                out.add(action(layer, "upgradeSubscription", Map.of("subscriptionId", s.id(), "targetOfferingId", top.id()),
                        s.name() + " could become " + top.name() + " (" + top.monthly() + " a month)", caller, 4));
            }
        }

        // money: the EFFECTIVE state, not the bill flag — the collection case (overdue, a promise to pay
        // that holds the ladder, a dispute or hardship hold) decides what the customer is actually in
        CustomerContext.Bill open = null;
        for (CustomerContext.Bill b : ctx.bills()) {
            String state = b.state() == null ? "" : b.state();
            double due;
            try {
                due = Double.parseDouble(b.amountDue() == null ? "0" : b.amountDue());
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
            situation.add(Situation.arranged(ccase.path("id").asText(), amount, currency, promise.path("dueAt").asText(""),
                    "a payment plan is agreed — " + amount + (currency.isEmpty() ? "" : " " + currency) + " by " + by + "; nothing else is due until then"));
            out.add(Recommendation.explain("explainBill", "Confirm the payment plan", "The customer promised " + amount + " by " + by + ". Collection is on hold until then; confirm the date, offer to take the payment early, and offer nothing else.", 3));
        } else if (caseOpen && ccase.path("holds").has("dispute")) {
            situation.add(Situation.disputed(ccase.path("id").asText(), ccase.path("overdueBalance").path("value").asText(""), currency,
                    "part of the bill is under dispute — collection waits while it is looked at"));
            out.add(Recommendation.explain("explainBill", "Walk through the disputed bill", "A dispute is open on this customer's bill; collection waits. Explain what is contested and what happens next.", 3));
        } else if (caseOpen) {
            String amount = ccase.path("overdueBalance").path("value").asText("");
            situation.add(Situation.overdue(ccase.path("id").asText(), amount, currency, ccase.path("stepIndex").asInt(0),
                    "an amount of " + amount + (currency.isEmpty() ? "" : " " + currency) + " is overdue — services are at risk until it is settled or a payment plan is agreed"));
            out.add(Recommendation.explain("explainBill", "Settle the overdue amount", "An amount of " + amount + " is overdue and the collection ladder is running. Take the payment or agree a promise to pay before anything else.", 1));
        } else if (open != null) {
            String state = open.state() == null ? "" : open.state();
            situation.add(Situation.bill(open.id(), open.amountDue(), "a bill of " + open.amountDue() + " is open (" + state + ")"));
            out.add(Recommendation.explain("explainBill", "Walk through the open bill", "A bill of " + open.amountDue() + " is " + state + ". Explain the lines before offering anything; a dispute pauses collection while it is looked at.", 3));
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
                String reason = item.path("reason").asText(item.path("description").asText(""));
                out.add(Recommendation.offer(item.path("offering").path("name").asText(offeringId), offeringId, 5 + shown,
                        reason.isEmpty() ? "picked from what this customer holds and looked at — nothing they already own" : reason,
                        "an offer to consider; the customer decides, and their verdict is remembered"));
                shown++;
            }
        }

        // receipts: what the BSS already decided about this customer, for the "why"
        List<CustomerContext.Receipt> receipts = ctx.receipts();
        // one recommendation per (action, what): a customer with several identical products is told once
        Map<String, Recommendation> unique = new LinkedHashMap<>();
        for (Recommendation rec : out) {
            unique.putIfAbsent(rec.dedupeKey(), rec);
        }
        out = new ArrayList<>(unique.values());
        // the loop: what this desk did with the same recommendation before moves it up or down —
        // the ontology's own decision log is the memory, read with the caller's rights
        Map<String, int[]> history = history(caller);
        List<Recommendation> ranked = new ArrayList<>();
        for (Recommendation rec : out) {
            int[] h = history.getOrDefault(rec.action(), new int[3]);
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
            ranked.add(rec.ranked(new Recommendation.Ranking(rec.priority(), shown, good, bad, adjustment, says), rec.priority() + adjustment));
        }
        ranked.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        // six things to do at most; offers ride along unclipped (there are never more than three)
        List<Recommendation> todo = ranked.stream().filter(r -> !"offer".equals(r.kind())).toList();
        List<Recommendation> offers = ranked.stream().filter(r -> "offer".equals(r.kind())).toList();
        if (todo.size() > 6) {
            todo = todo.subList(0, 6);
        }
        List<Recommendation> chosen = new ArrayList<>(todo);
        chosen.addAll(offers);
        // every recommendation shown is a decision of the BSS, so its outcome can be learned from
        List<String> candidates = chosen.stream().map(Recommendation::action).toList();
        List<String> eligible = chosen.stream().filter(Recommendation::allowed).map(Recommendation::action).toList();
        out = new ArrayList<>();
        for (Recommendation shown : chosen) {
            String decisionId = "rec-" + java.util.UUID.randomUUID();
            Recommendation rec = shown.decided(decisionId);
            out.add(rec);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("decisionId", decisionId);
            d.put("decisionPoint", DECISION_POINT);
            d.put("subjectType", "customer");
            d.put("subjectId", customerId);
            d.put("candidates", candidates);
            d.put("eligibleActions", eligible);
            d.put("constraints", List.of("dry-run through the registry before it is shown", "the agent decides; the desk only recommends"));
            d.put("action", rec.action());
            d.put("propensity", null);
            d.put("policy", "assist-ranking");
            d.put("policyVersion", "1");
            d.put("reason", rec.why());
            Map<String, Object> dctx = new LinkedHashMap<>();
            dctx.put("kind", rec.kind());
            dctx.put("rank", rec.rank());
            dctx.put("ranking", rec.ranking());
            dctx.put("agent", ActionExecuteService.agentLabel(registry, caller));
            if (rec.offeringId() != null) {
                dctx.put("offeringId", rec.offeringId());
            }
            d.put("context", dctx);
            d.put("evidence", Map.of("situation", situation.stream().map(Situation::says).toList()));
            d.put("autonomy", "assist");
            d.put("fallback", false);
            d.put("source", "ontology");
            d.put("contract", null);
            d.put("decidedAt", OffsetDateTime.now().toString());
            d.put("@type", "Decision");
            publisher.decision(caller.tenant(), d);
        }
        // the situation SUMMARISED: one entry per kind with a severity, whether the customer must act,
        // and how many lines it covers — what a Home or a desk shows, instead of one row per event
        String said = out.isEmpty() ? "Nothing stands out: no open incident, no paused line, no open bill, no dearer plan in the family. Listen first."
                : "Top recommendation: " + out.get(0).title() + " — " + out.get(0).why();
        return new CustomerRecommendations(customerId, situation, summarize(situation), worstSeverity(situation),
                situation.stream().noneMatch(s -> !"info".equals(s.severity())), out, receipts.stream().limit(5).toList(),
                ctx.unanswered(), said, "CustomerRecommendations");
    }

    /** The agent's verdict on a recommendation, back into the decision log where the ranking reads it. */
    public RecommendationOutcome outcome(String decisionId, String outcome, String reason, Caller caller) {
        if (decisionId == null || !decisionId.startsWith("rec-")) {
            throw new IllegalArgumentException("not a recommendation decision id: " + decisionId);
        }
        if (outcome == null || !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("outcome must be one of " + OUTCOMES);
        }
        publisher.outcome(caller.tenant(), decisionId, outcome, reason == null || reason.isBlank() ? null : reason);
        return new RecommendationOutcome(decisionId, outcome,
                "Noted: the recommendation was " + outcome + (reason == null || reason.isBlank() ? "" : " (" + reason + ")") + ". The ranking on this desk learns from it.",
                "RecommendationOutcome");
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

    private Recommendation action(Registry.Layer layer, String name, Map<String, String> inputs, String because, Caller caller, int priority) {
        JsonNode def = layer.actions().get(name);
        if (def == null) {
            return Recommendation.unregistered(name, ExplainService.title(name), inputs, priority, because + " — but the action is not in this tenant's registry");
        }
        Check c = checks.check(def, inputs, caller);
        return Recommendation.action(name, ExplainService.title(name), inputs, priority, c,
                because + (c.allowed() ? "; every condition holds for this caller" : "; it cannot happen now: " + c.refusal()),
                def.path("meaning").asText(), def.path("governance").path("autonomy").asText());
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

    static String worstSeverity(List<Situation> situation) {
        String worst = "info";
        for (Situation s : situation) {
            String sev = s.severity() == null ? "info" : s.severity();
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
    static List<SituationSummary> summarize(List<Situation> situation) {
        Map<String, List<Situation>> byKind = new LinkedHashMap<>();
        for (Situation s : situation) {
            byKind.computeIfAbsent(s.kind(), k -> new ArrayList<>()).add(s);
        }
        List<SituationSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<Situation>> e : byKind.entrySet()) {
            List<Situation> members = e.getValue();
            Situation first = members.get(0);
            int n = members.size();
            String says = switch (e.getKey()) {
                case "paused" -> n == 1 ? first.says() : n + " services are paused — nothing is charged and nothing connects until they are resumed";
                case "incident" -> n == 1 ? first.says() : n + " network incidents affect this customer's lines — the network team is on them; nothing to do";
                default -> first.says();
            };
            out.add(new SituationSummary(e.getKey(), first.severity() == null ? "info" : first.severity(), first.actionRequired(), n,
                    members.stream().map(Situation::id).toList(), says, first.amount(), first.currency(), first.dueAt(), first.name()));
        }
        List<String> order = List.of("critical", "warning", "info");
        out.sort((a, b) -> Integer.compare(order.indexOf(a.severity()), order.indexOf(b.severity())));
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
