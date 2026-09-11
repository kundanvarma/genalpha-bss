package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "May this happen, and if not, why?" — every precondition of the action is
 * evaluated against the resolved objects, every permission clause against the
 * caller, and the policy domain against the registry's machine identity. A
 * verdict is true, false, or unknown (a seam that did not answer: the action is
 * not refused for it, and the receipt says so). The refusal names the condition
 * in the action's own words — the same words the console, the SDK and an agent see.
 */
@Service
public class ActionCheckService {

    public record Verdict(String id, String says, Boolean ok, String detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("says", says);
            m.put("verdict", ok == null ? "unknown" : ok ? "holds" : "fails");
            if (detail != null) {
                m.put("detail", detail);
            }
            return m;
        }
    }

    public record Check(boolean allowed, List<Verdict> preconditions, Map<String, Object> permission,
            Map<String, Object> policy, Resolver.Resolved resolved, String refusal) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowed", allowed);
            if (refusal != null) {
                m.put("refusal", refusal);
            }
            m.put("preconditions", preconditions.stream().map(Verdict::toMap).toList());
            m.put("permission", permission);
            m.put("policy", policy);
            return m;
        }
    }

    private final Registry registry;
    private final Resolver resolver;
    private final ComponentClient client;

    public ActionCheckService(Registry registry, Resolver resolver, ComponentClient client) {
        this.registry = registry;
        this.resolver = resolver;
        this.client = client;
    }

    public JsonNode actionOf(String name, Caller caller) {
        return registry.forTenant(caller.tenant()).actions().get(name);
    }

    public Check check(JsonNode action, Map<String, String> inputs, Caller caller) {
        Registry.Layer layer = registry.forTenant(caller.tenant());
        Resolver.Resolved r = resolver.resolve(action, inputs, caller);
        List<Verdict> verdicts = new ArrayList<>();
        for (JsonNode pc : action.path("preconditions")) {
            verdicts.add(evaluate(pc, action, inputs, r, caller, layer));
        }
        Map<String, Object> permission = permission(action, r, caller);
        Map<String, Object> policy = policy(action, inputs, r, caller, layer);
        String refusal = null;
        for (String p : r.problems) {
            if (refusal == null && p.contains("is required")) {
                refusal = p;
            }
        }
        for (Verdict v : verdicts) {
            if (refusal == null && Boolean.FALSE.equals(v.ok())) {
                refusal = v.says() + (v.detail() == null ? "" : " — " + v.detail());
            }
        }
        if (refusal == null && !Boolean.TRUE.equals(permission.get("ok"))) {
            refusal = String.valueOf(permission.get("says"));
        }
        if (refusal == null && "deny".equals(policy.get("decision"))) {
            refusal = "a business rule refuses it: " + policy.getOrDefault("message", policy.get("ruleName"));
        }
        return new Check(refusal == null, verdicts, permission, policy, r, refusal);
    }

    /* ------------------------------------------------------------------ preconditions */

    private Verdict evaluate(JsonNode pc, JsonNode action, Map<String, String> inputs, Resolver.Resolved r, Caller caller,
            Registry.Layer layer) {
        String id = pc.path("id").asText();
        String says = pc.path("says").asText();
        try {
            switch (pc.path("check").asText()) {
                case "input" -> {
                    String v = inputs.get(pc.path("of").asText());
                    return new Verdict(id, says, v != null && !v.isBlank(), null);
                }
                case "state" -> {
                    String key = Resolver.keyOf(pc.path("of").asText());
                    JsonNode obj = r.get(key);
                    if (obj == null) {
                        JsonNode any = r.get(key + "AnyChannel");
                        if (any == null) {
                            return new Verdict(id, says, false, key + " could not be read: " + firstProblem(r, key));
                        }
                        obj = any;
                    }
                    JsonNode concept = layer.concepts().get(pc.path("concept").asText());
                    String field = concept.path("states").path("field").asText();
                    String value = obj.path(field).asText("");
                    boolean ok = false;
                    for (JsonNode allowed : pc.path("in")) {
                        if (allowed.asText().equalsIgnoreCase(value)) {
                            ok = true;
                        }
                    }
                    return new Verdict(id, says, ok, "it is " + (value.isEmpty() ? "in no state" : "\"" + value + "\""));
                }
                case "function" -> {
                    List<String> args = new ArrayList<>();
                    pc.path("args").forEach(a -> args.add(a.asText()));
                    return function(id, says, pc.path("function").asText(), args, inputs, r, caller);
                }
                case "capability" -> {
                    return capability(id, says, pc, inputs, r, caller, layer);
                }
                default -> {
                    return new Verdict(id, says, null, "unknown check kind " + pc.path("check").asText());
                }
            }
        } catch (RuntimeException e) {
            return new Verdict(id, says, null, "could not be evaluated: " + e.getMessage());
        }
    }

    private static String firstProblem(Resolver.Resolved r, String key) {
        for (String p : r.problems) {
            if (p.startsWith(key + " ")) {
                return p.substring(key.length() + 1);
            }
        }
        return "no answer";
    }

    private Verdict function(String id, String says, String fn, List<String> args, Map<String, String> inputs,
            Resolver.Resolved r, Caller caller) {
        String a0 = args.isEmpty() ? "" : Resolver.keyOf(args.get(0));
        String a1 = args.size() < 2 ? "" : Resolver.keyOf(args.get(1));
        switch (fn) {
            case "sellableInChannel" -> {
                if (r.has(a0)) {
                    return new Verdict(id, says, true, "sold on channel \"" + caller.channel() + "\"");
                }
                boolean existsElsewhere = r.has(a0 + "AnyChannel");
                return new Verdict(id, says, false, existsElsewhere
                        ? "not sold on channel \"" + caller.channel() + "\"" : "the offering could not be read: " + firstProblem(r, a0));
            }
            case "differentOffering" -> {
                JsonNode sub = r.get(a0);
                String current = sub == null ? "" : sub.path("productOffering").path("id").asText();
                String target = inputs.getOrDefault(args.get(1), "");
                return new Verdict(id, says, !current.isEmpty() && !current.equals(target), null);
            }
            case "sameCategory" -> {
                JsonNode cur = r.get("currentOffering");
                JsonNode target = firstOf(r, a1);
                if (cur == null || target == null) {
                    return new Verdict(id, says, null, "an offering could not be read");
                }
                String c1 = cur.path("category").path(0).path("name").asText();
                String c2 = target.path("category").path(0).path("name").asText();
                return new Verdict(id, says, !c1.isEmpty() && c1.equalsIgnoreCase(c2), "\"" + c1 + "\" → \"" + c2 + "\"");
            }
            case "notBundle" -> {
                JsonNode target = firstOf(r, a0);
                return target == null ? new Verdict(id, says, null, "the offering could not be read")
                        : new Verdict(id, says, !target.path("isBundle").asBoolean(false), null);
            }
            case "higherMonthlyPrice" -> {
                BigDecimal cur = resolver.monthlyOf("currentOffering", r, caller);
                String targetKey = r.has(a1) ? a1 : a1 + "AnyChannel";
                BigDecimal target = resolver.monthlyOf(targetKey, r, caller);
                if (cur == null || target == null) {
                    return new Verdict(id, says, null, "a monthly price could not be read");
                }
                return new Verdict(id, says, target.compareTo(cur) > 0, cur.toPlainString() + " → " + target.toPlainString() + " per month");
            }
            case "monthlyPriceAtMost" -> {
                String targetKey = r.has(a0) ? a0 : a0 + "AnyChannel";
                BigDecimal target = resolver.monthlyOf(targetKey, r, caller);
                BigDecimal limit = new BigDecimal(args.get(1));
                if (target == null) {
                    return new Verdict(id, says, null, "the monthly price could not be read");
                }
                return new Verdict(id, says, target.compareTo(limit) <= 0, target.toPlainString() + " against a ceiling of " + limit.toPlainString());
            }
            case "noActiveCommitment" -> {
                return noActiveCommitment(id, says, r, caller);
            }
            case "oneOf" -> {
                String v = inputs.getOrDefault(args.get(0), "").trim().toLowerCase();
                List<String> allowed = List.of(args.get(1).split(","));
                return new Verdict(id, says, !v.isEmpty() && allowed.contains(v), v.isEmpty() ? "none given" : "\"" + v + "\"");
            }
            case "amountAtMost" -> {
                String raw = inputs.getOrDefault(args.get(0), "");
                if (raw.isBlank()) {
                    return new Verdict(id, says, false, "no amount given");
                }
                try {
                    BigDecimal amount = new BigDecimal(raw);
                    BigDecimal limit = new BigDecimal(args.get(1));
                    return new Verdict(id, says, amount.compareTo(limit) <= 0, amount.toPlainString() + " against a ceiling of " + limit.toPlainString());
                } catch (NumberFormatException e) {
                    return new Verdict(id, says, false, "\"" + raw + "\" is not an amount");
                }
            }
            case "creditWithinDue" -> {
                JsonNode bill = r.get(a0);
                String raw = inputs.getOrDefault(args.get(1), "");
                if (bill == null) {
                    return new Verdict(id, says, null, "the bill could not be read");
                }
                try {
                    BigDecimal amount = new BigDecimal(raw);
                    JsonNode dueNode = bill.path("amountDue");
                    BigDecimal due = new BigDecimal(dueNode.isObject() ? dueNode.path("value").asText("0") : dueNode.asText("0"));
                    boolean ok = amount.signum() > 0 && amount.compareTo(due) <= 0;
                    return new Verdict(id, says, ok, amount.toPlainString() + " against " + due.toPlainString() + " still due");
                } catch (NumberFormatException e) {
                    return new Verdict(id, says, false, "\"" + raw + "\" is not an amount");
                }
            }
            case "governanceStateIn" -> {
                return governanceStateIn(id, says, inputs.getOrDefault(args.get(0), ""), args.get(1), caller);
            }
            default -> {
                return new Verdict(id, says, null, "unknown function " + fn);
            }
        }
    }

    private static JsonNode firstOf(Resolver.Resolved r, String key) {
        return r.has(key) ? r.get(key) : r.get(key + "AnyChannel");
    }

    private Verdict noActiveCommitment(String id, String says, Resolver.Resolved r, Caller caller) {
        JsonNode owner = r.get("owner");
        JsonNode sub = r.get("subscription");
        if (owner == null || sub == null) {
            return new Verdict(id, says, null, "the subscription could not be read");
        }
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("agreement.list");
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of(), Map.of("relatedPartyId", owner.path("id").asText(), "limit", "100"), null, caller.bearer(), Map.of());
        if (!reply.ok() || !reply.body().isArray()) {
            return new Verdict(id, says, null, "agreements could not be read (" + Resolver.statusWords(reply) + ") — the order desk checks again at execution");
        }
        String currentOffering = sub.path("productOffering").path("id").asText();
        OffsetDateTime now = OffsetDateTime.now();
        for (JsonNode ag : reply.body()) {
            String end = ag.path("agreementPeriod").path("endDateTime").asText("");
            if (end.isEmpty()) {
                continue;
            }
            try {
                if (OffsetDateTime.parse(end).isBefore(now)) {
                    continue;
                }
            } catch (Exception badDate) {
                continue;
            }
            for (JsonNode item : ag.path("agreementItem")) {
                if (currentOffering.equals(item.path("productOffering").path("id").asText())) {
                    return new Verdict(id, says, false, "under a commitment until " + end.substring(0, Math.min(10, end.length())));
                }
            }
        }
        return new Verdict(id, says, true, null);
    }

    private Verdict governanceStateIn(String id, String says, String offeringId, String allowedCsv, Caller caller) {
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get("catalog.governanceView");
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                Map.of("id", offeringId), Map.of(), null, caller.bearer(), Map.of());
        if (!reply.ok()) {
            return new Verdict(id, says, null, "the launch state could not be read (" + Resolver.statusWords(reply) + ")");
        }
        String state = reply.body().path("governanceState").asText(reply.body().path("state").asText("none"));
        if (state.isEmpty()) {
            state = "none";
        }
        boolean ok = List.of(allowedCsv.split(",")).contains(state);
        return new Verdict(id, says, ok, "its launch state is \"" + state + "\"");
    }

    private Verdict capability(String id, String says, JsonNode pc, Map<String, String> inputs, Resolver.Resolved r,
            Caller caller, Registry.Layer layer) {
        String capId = pc.path("capability").asText();
        JsonNode cap = layer.capabilities().get(capId);
        if ("productQualification.check".equals(capId)) {
            String offeringId = inputs.getOrDefault(pc.path("of").asText(), "");
            String postCode = Resolver.postCodeOf(r.get("customer"));
            Map<String, Object> body = Map.of("productOfferingQualificationItem", List.of(Map.of(
                    "productOffering", Map.of("id", offeringId), "place", Map.of("postCode", postCode))));
            ComponentClient.Reply reply = client.call(cap.path("component").asText(), "POST", cap.path("route").path("path").asText(),
                    Map.of(), Map.of(), body, caller.bearer(), Map.of("X-Tenant-Id", caller.tenant()));
            if (!reply.ok()) {
                return new Verdict(id, says, null, "qualification did not answer (" + Resolver.statusWords(reply) + ")");
            }
            String result = reply.body().path("qualificationResult").asText();
            boolean ok = pc.path("expect").asText("qualified").equalsIgnoreCase(result);
            String why = null;
            JsonNode item = reply.body().path("productOfferingQualificationItem").path(0);
            if (!ok) {
                why = item.path("eligibilityUnavailabilityReason").path(0).path("label").asText("not qualified");
            } else if (!item.path("serviceabilityGated").asBoolean(false)) {
                why = "not place-gated";
            } else {
                why = "deliverable at " + postCode;
            }
            return new Verdict(id, says, ok, why);
        }
        return new Verdict(id, says, null, "no evaluator for capability " + capId);
    }

    /* ------------------------------------------------------------------ permissions */

    private Map<String, Object> permission(JsonNode action, Resolver.Resolved r, Caller caller) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> tried = new ArrayList<>();
        for (JsonNode clause : action.path("permissions").path("anyOf")) {
            if (clause.has("self")) {
                String selfName = clause.path("self").asText();
                JsonNode owner = r.get(selfName);
                if (owner == null && ("owner".equals(selfName) || "customer".equals(selfName))) {
                    owner = r.get("owner");
                }
                boolean ok = owner != null && !owner.path("id").asText().isEmpty()
                        && owner.path("id").asText().equals(caller.subject());
                tried.add("as the " + clause.path("self").asText() + (ok ? " — yes" : " — no"));
                if (ok) {
                    out.put("ok", true);
                    out.put("by", "self:" + clause.path("self").asText());
                    out.put("says", "the caller is the " + clause.path("self").asText() + " of the subscription");
                    out.put("tried", tried);
                    return out;
                }
            } else if (clause.has("role")) {
                String role = clause.path("role").asText();
                // a customer's token may hold the role too; it still only acts on its own things
                boolean ok = caller.has(role) && !caller.isCustomer();
                tried.add("with role " + role + (ok ? " — yes" : caller.isCustomer() ? " — no, a customer acts only on their own line" : " — no"));
                if (ok) {
                    out.put("ok", true);
                    out.put("by", "role:" + role);
                    out.put("says", "the caller holds " + role);
                    out.put("tried", tried);
                    return out;
                }
            }
        }
        out.put("ok", false);
        out.put("says", caller.isCustomer()
                ? "this is not your subscription"
                : "the caller holds none of the roles this action needs");
        out.put("tried", tried);
        return out;
    }

    /* ------------------------------------------------------------------ policy */

    private Map<String, Object> policy(JsonNode action, Map<String, String> inputs, Resolver.Resolved r, Caller caller,
            Registry.Layer layer) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!action.has("policy")) {
            out.put("decision", "none");
            out.put("says", "no policy domain applies to this action");
            return out;
        }
        String domain = action.path("policy").path("domain").asText();
        JsonNode cap = layer.capabilities().get(action.path("policy").path("capability").asText());
        Map<String, Object> context = new LinkedHashMap<>();
        JsonNode owner = r.get("owner");
        context.put("party", owner == null ? null : owner.path("id").asText());
        context.put("action", action.path("action").asText());
        context.put("channel", caller.channel());
        JsonNode target = firstOf(r, "targetOffering");
        String targetId = inputs.getOrDefault("targetOfferingId", "");
        if (!targetId.isEmpty()) {
            context.put("items", List.of(Map.of("offeringId", targetId, "name", target == null ? "" : target.path("name").asText(), "quantity", 1)));
            context.put("offeringIds", List.of(targetId));
            context.put("quantityByOffering", Map.of(targetId, 1));
            context.put("maxLineQuantity", 1);
            context.put("totalQuantity", 1);
            context.put("lineCount", 1);
        }
        context.put("verifiedIdentity", false);
        ComponentClient.Reply reply = client.callAsMachine(cap.path("component").asText(), "POST", cap.path("route").path("path").asText(),
                Map.of(), Map.of("domain", domain, "context", context), Map.of());
        out.put("domain", domain);
        if (!reply.ok()) {
            out.put("decision", "unknown");
            out.put("says", "the policy service did not answer (" + Resolver.statusWords(reply) + "); the order desk enforces the same rules at execution");
            return out;
        }
        out.put("decision", reply.body().path("decision").asText("allow"));
        if (reply.body().has("ruleName")) {
            out.put("ruleName", reply.body().path("ruleName").asText());
        }
        if (reply.body().has("message")) {
            out.put("message", reply.body().path("message").asText());
        }
        out.put("says", "deny".equals(out.get("decision"))
                ? "rule \"" + out.get("ruleName") + "\" refuses: " + out.get("message")
                : reply.body().has("ruleName") ? "allowed by rule \"" + out.get("ruleName") + "\"" : "no rule in domain \"" + domain + "\" objects");
        return out;
    }
}
