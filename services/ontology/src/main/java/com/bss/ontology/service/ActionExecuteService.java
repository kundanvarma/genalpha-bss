package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Execute = check, then do, then write the receipt. "Do" is the action's
 * capability, called with the caller's own token and channel, with a request
 * built from the action's template over the inputs and the resolved objects.
 * The registry adds nothing the component would not have demanded itself —
 * it only says, before and after, what happened and why.
 */
@Service
public class ActionExecuteService {

    public record Outcome(boolean done, int status, Map<String, Object> body) { }

    private final Registry registry;
    private final ActionCheckService checks;
    private final ComponentClient client;
    private final ReceiptPublisher receipts;
    private final UpgradeService upgrades;
    private final ObjectMapper json;

    public ActionExecuteService(Registry registry, ActionCheckService checks, ComponentClient client,
            ReceiptPublisher receipts, UpgradeService upgrades, ObjectMapper json) {
        this.registry = registry;
        this.checks = checks;
        this.client = client;
        this.receipts = receipts;
        this.upgrades = upgrades;
        this.json = json;
    }

    public Outcome execute(JsonNode action, Map<String, String> inputs, Caller caller) {
        String name = action.path("action").asText();
        ActionCheckService.Check check = checks.check(action, inputs, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", name);
        out.put("check", check.toMap());
        if (!check.allowed()) {
            out.put("done", false);
            out.put("refusal", check.refusal());
            out.put("said", "Refused: " + check.refusal() + ".");
            receipt(action, inputs, caller, check, null, "refused", check.refusal());
            return new Outcome(false, 422, out);
        }
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get(action.path("executes").path("capability").asText());
        JsonNode template = action.path("executes").path("template");
        List<String> missing = new ArrayList<>();
        JsonNode body = fill(template, inputs, check.resolved(), missing);
        if (!missing.isEmpty()) {
            String why = "the request could not be built: " + String.join(", ", missing);
            out.put("done", false);
            out.put("refusal", why);
            out.put("said", "Refused: " + why + ".");
            receipt(action, inputs, caller, check, null, "refused", why);
            return new Outcome(false, 422, out);
        }
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), cap.path("route").path("method").asText(),
                cap.path("route").path("path").asText(), Map.of(), Map.of(), body, caller.bearer(),
                Map.of(Caller.CHANNEL_HEADER, caller.channel()));
        out.put("executedBy", Map.of("component", cap.path("component").asText(), "capability", cap.path("id").asText(),
                "route", cap.path("route").path("method").asText() + " " + cap.path("route").path("path").asText()));
        if (!reply.ok()) {
            String why = componentWords(reply);
            out.put("done", false);
            out.put("refusal", why);
            out.put("said", "The " + cap.path("component").asText() + " component refused: " + why);
            out.put("componentStatus", reply.status());
            receipt(action, inputs, caller, check, null, "refused-by-component", why);
            return new Outcome(false, reply.status() >= 400 && reply.status() < 500 ? reply.status() : 502, out);
        }
        JsonNode result = reply.body();
        String decisionId = receipt(action, inputs, caller, check, result, "executed", null);
        out.put("done", true);
        out.put("result", json.convertValue(result, Map.class));
        out.put("decisionId", decisionId);
        out.put("effects", json.convertValue(action.path("effects"), List.class));
        out.put("emits", json.convertValue(action.path("emits"), List.class));
        out.put("said", said(action, check, result, cap));
        return new Outcome(true, 200, out);
    }

    /* ------------------------------------------------------------------ the request */

    private JsonNode fill(JsonNode node, Map<String, String> inputs, Resolver.Resolved r, List<String> missing) {
        if (node.isTextual()) {
            String s = node.asText();
            if (s.startsWith("${") && s.endsWith("}")) {
                String path = s.substring(2, s.length() - 1);
                String v = lookup(path, inputs, r);
                if (v == null) {
                    missing.add(path + " is not known");
                    return TextNode.valueOf("");
                }
                return TextNode.valueOf(v);
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode o = json.createObjectNode();
            node.fields().forEachRemaining(f -> o.set(f.getKey(), fill(f.getValue(), inputs, r, missing)));
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = json.createArrayNode();
            node.forEach(n -> a.add(fill(n, inputs, r, missing)));
            return a;
        }
        return node;
    }

    private static String lookup(String path, Map<String, String> inputs, Resolver.Resolved r) {
        if (inputs.containsKey(path)) {
            return inputs.get(path);
        }
        int dot = path.indexOf('.');
        String key = dot < 0 ? path : path.substring(0, dot);
        JsonNode obj = r.has(key) ? r.get(key) : r.get(key + "AnyChannel");
        if (obj == null) {
            return null;
        }
        if (dot < 0) {
            return obj.isValueNode() ? obj.asText() : null;
        }
        JsonNode v = obj;
        for (String seg : path.substring(dot + 1).split("\\.")) {
            v = v.path(seg);
        }
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String componentWords(ComponentClient.Reply reply) {
        JsonNode b = reply.body();
        for (String k : new String[] {"message", "detail", "reason", "error"}) {
            if (b.has(k) && !b.path(k).asText().isBlank()) {
                return b.path(k).asText();
            }
        }
        return Resolver.statusWords(reply);
    }

    private String said(JsonNode action, ActionCheckService.Check check, JsonNode result, JsonNode cap) {
        JsonNode target = check.resolved().has("targetOffering") ? check.resolved().get("targetOffering")
                : check.resolved().get("targetOfferingAnyChannel");
        JsonNode sub = check.resolved().get("subscription");
        StringBuilder sb = new StringBuilder();
        sb.append("Done: ").append(action.path("action").asText());
        if (sub != null && target != null) {
            sb.append(" — \"").append(sub.path("name").asText()).append("\" becomes \"").append(target.path("name").asText()).append("\"");
        }
        sb.append(". ").append(check.preconditions().size()).append(" conditions held");
        long unknown = check.preconditions().stream().filter(v -> v.ok() == null).count();
        if (unknown > 0) {
            sb.append(" (").append(unknown).append(" could not be checked here and were left to the component)");
        }
        sb.append("; permission ").append(check.permission().get("by")).append("; policy ").append(check.policy().get("decision")).append(". ");
        sb.append("Executed by ").append(cap.path("component").asText()).append(" as order ")
                .append(result.path("id").asText("?")).append(" in state ").append(result.path("state").asText("?")).append(". ");
        sb.append("What follows: ");
        List<String> effects = new ArrayList<>();
        for (JsonNode e : action.path("effects")) {
            effects.add(e.path("capability").asText() + (e.has("when") ? " (" + e.path("when").asText() + ")" : ""));
        }
        sb.append(String.join(", ", effects)).append(".");
        return sb.toString();
    }

    /* ------------------------------------------------------------------ the receipt */

    private String receipt(JsonNode action, Map<String, String> inputs, Caller caller, ActionCheckService.Check check,
            JsonNode result, String outcome, String why) {
        String decisionId = "ontology-" + UUID.randomUUID();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decisionId", decisionId);
        d.put("decisionPoint", "ontology." + action.path("action").asText());
        d.put("subjectType", action.path("concept").asText().toLowerCase());
        d.put("subjectId", inputs.getOrDefault("subscriptionId", inputs.values().stream().findFirst().orElse("")));
        List<String> candidates = new ArrayList<>();
        try {
            for (Map<String, Object> u : upgrades.availableUpgrades(check.resolved(), caller)) {
                candidates.add(String.valueOf(u.get("id")));
            }
        } catch (RuntimeException ignored) {
            // candidates are context, never a reason to lose the receipt
        }
        String chosen = inputs.getOrDefault("targetOfferingId", "");
        if (!chosen.isEmpty() && !candidates.contains(chosen)) {
            candidates.add(chosen);
        }
        d.put("candidates", candidates);
        d.put("eligibleActions", "executed".equals(outcome) ? candidates : List.of());
        List<String> constraints = new ArrayList<>();
        for (ActionCheckService.Verdict v : check.preconditions()) {
            if (v.ok() == null) {
                constraints.add("unchecked: " + v.id() + (v.detail() == null ? "" : " — " + v.detail()));
            }
            if (Boolean.FALSE.equals(v.ok())) {
                constraints.add("failed: " + v.id() + (v.detail() == null ? "" : " — " + v.detail()));
            }
        }
        d.put("constraints", constraints);
        d.put("action", "executed".equals(outcome) ? chosen : "refused");
        d.put("propensity", null);
        d.put("policy", "operational-semantic-registry");
        d.put("policyVersion", String.valueOf(action.path("version").asInt(1)));
        d.put("reason", "executed".equals(outcome)
                ? "every precondition of " + action.path("action").asText() + " held, permission " + check.permission().get("by")
                        + ", policy " + check.policy().get("decision") + "; order " + (result == null ? "?" : result.path("id").asText("?"))
                : "refused: " + why);
        Map<String, Object> context = new LinkedHashMap<>(inputs);
        context.put("channel", caller.channel());
        context.put("callerKind", caller.isCustomer() ? "customer" : "staff");
        d.put("context", context);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("preconditions", check.preconditions().stream().map(ActionCheckService.Verdict::toMap).toList());
        evidence.put("permission", check.permission());
        evidence.put("policy", check.policy());
        d.put("evidence", evidence);
        d.put("autonomy", action.path("governance").path("autonomy").asText("medium"));
        d.put("fallback", false);
        d.put("source", "ontology");
        d.put("contract", null);
        d.put("decidedAt", OffsetDateTime.now().toString());
        d.put("@type", "Decision");
        receipts.decision(caller.tenant(), d);
        if ("executed".equals(outcome) && result != null && "completed".equalsIgnoreCase(result.path("state").asText())) {
            receipts.outcome(caller.tenant(), decisionId, "completed", result.path("id").asText());
        }
        return decisionId;
    }
}
