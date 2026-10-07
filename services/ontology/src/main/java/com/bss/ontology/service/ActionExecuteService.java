package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.ExecuteReceipt;
import com.bss.ontology.dto.UpgradeOption;
import com.bss.ontology.dto.Verdict;
import com.bss.ontology.registry.Registry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
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

    public record Outcome(boolean done, int status, ExecuteReceipt body) { }

    /** The receipt as it is written, path by path; {@link #receipt()} freezes it for the wire. */
    private static final class Draft {
        final String action;
        Check check;
        String executedAs;
        ExecuteReceipt.ExecutedBy executedBy;
        boolean done;
        Boolean filed;
        JsonNode result;
        String decisionId;
        String approvalId;
        String refusal;
        Integer componentStatus;
        JsonNode effects;
        JsonNode emits;
        String said;

        Draft(String action) {
            this.action = action;
        }

        Outcome refused(int status, String why) {
            done = false;
            refusal = why;
            said = "Refused: " + why + ".";
            return new Outcome(false, status, receipt());
        }

        ExecuteReceipt receipt() {
            return new ExecuteReceipt(action, check, executedAs, executedBy, done, filed, result, decisionId, approvalId, refusal,
                    componentStatus, effects, emits, said);
        }
    }

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
        String name = action.path("action").asString();
        Draft out = new Draft(name);
        // a retired action refuses past its date and points at its successor; until then it still runs
        if ("deprecated".equals(action.path("status").asString())) {
            String until = action.path("deprecated").asString("");
            boolean past = !until.isEmpty() && java.time.LocalDate.parse(until).isBefore(java.time.LocalDate.now());
            if (past) {
                return out.refused(410, name + " was retired on " + until
                        + (action.has("supersededBy") ? "; use " + action.path("supersededBy").asString() : ""));
            }
        }
        Check check = checks.check(action, inputs, caller);
        out.check = check;
        if (!check.allowed()) {
            receipt(action, inputs, caller, check, null, "refused", check.refusal());
            return out.refused(422, check.refusal());
        }
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode cap = layer.capabilities().get(action.path("executes").path("capability").asString());
        JsonNode template = action.path("executes").path("template");
        List<String> missing = new ArrayList<>();
        JsonNode body = fill(template, action, inputs, check.resolved(), missing);
        if (!missing.isEmpty()) {
            String why = "the request could not be built: " + String.join(", ", missing);
            receipt(action, inputs, caller, check, null, "refused", why);
            return out.refused(422, why);
        }
        Map<String, String> pathVars = new LinkedHashMap<>();
        action.path("executes").path("pathVars").properties().forEach(f -> {
            String v = lookup(strip(f.getValue().asString()), inputs, check.resolved());
            if (v == null) {
                missing.add(f.getValue().asString() + " is not known");
            } else {
                pathVars.put(f.getKey(), v);
            }
        });
        Map<String, String> query = new LinkedHashMap<>();
        action.path("executes").path("query").properties().forEach(f -> {
            String v = lookup(strip(f.getValue().asString()), inputs, check.resolved());
            if (v != null) {
                query.put(f.getKey(), v);
            }
        });
        if (!missing.isEmpty()) {
            return out.refused(422, "the request could not be built: " + String.join(", ", missing));
        }
        JsonNode governance = action.path("governance");
        String approverRole = governance.path("approverRole").asString("");
        boolean isApprover = !approverRole.isEmpty() && caller.has(approverRole);
        boolean needsHuman = "human".equals(governance.path("approval").asString()) && !isApprover;
        if (needsHuman && governance.has("approvalAbove")) {
            String input = governance.path("approvalAbove").path("input").asString();
            try {
                java.math.BigDecimal value = new java.math.BigDecimal(inputs.getOrDefault(input, ""));
                java.math.BigDecimal threshold = new java.math.BigDecimal(governance.path("approvalAbove").path("amount").asString("0"));
                needsHuman = value.compareTo(threshold) > 0;
            } catch (NumberFormatException e) {
                needsHuman = true;
            }
        }
        if (needsHuman) {
            return fileForApproval(action, cap, pathVars, body, inputs, caller, check, out);
        }
        boolean asRegistry = "registry".equals(action.path("executes").path("as").asString("caller")) && !isApprover;
        Object payload = template.isMissingNode() || template.isNull() ? null : body;
        ComponentClient.Reply reply = asRegistry
                ? client.callAsMachine(cap.path("component").asString(), cap.path("route").path("method").asString(),
                        fillPath(cap.path("route").path("path").asString(), pathVars), query, payload, Map.of(Caller.CHANNEL_HEADER, caller.channel()))
                : client.call(cap.path("component").asString(), cap.path("route").path("method").asString(),
                        cap.path("route").path("path").asString(), pathVars, query, payload, caller.bearer(), Map.of(Caller.CHANNEL_HEADER, caller.channel()));
        out.executedAs = asRegistry ? "the registry's own identity — the action's permission model governed this, and the receipt names " + caller.subject() : "the caller";
        out.executedBy = new ExecuteReceipt.ExecutedBy(cap.path("component").asString(), cap.path("id").asString(),
                cap.path("route").path("method").asString() + " " + cap.path("route").path("path").asString());
        if (!reply.ok()) {
            String why = componentWords(reply);
            out.done = false;
            out.refusal = why;
            out.said = "The " + cap.path("component").asString() + " component refused: " + why;
            out.componentStatus = reply.status();
            receipt(action, inputs, caller, check, null, "refused-by-component", why);
            return new Outcome(false, reply.status() >= 400 && reply.status() < 500 ? reply.status() : 502, out.receipt());
        }
        JsonNode result = reply.body();
        String decisionId = receipt(action, inputs, caller, check, result, "executed", null);
        out.done = true;
        out.result = result.isMissingNode() ? json.getNodeFactory().nullNode() : result;
        out.decisionId = decisionId;
        out.effects = action.path("effects");
        out.emits = action.path("emits");
        out.said = said(action, check, result, cap);
        return new Outcome(true, 200, out.receipt());
    }

    private static String fillPath(String path, Map<String, String> pathVars) {
        String p = path;
        for (Map.Entry<String, String> v : pathVars.entrySet()) {
            p = p.replace("{" + v.getKey() + "}", v.getValue());
        }
        return p;
    }

    /**
     * Above the threshold (or when the action always needs a human) the request is filed on the
     * workforce approval desk: an approver holding the approver role executes it from there with
     * their own token. The receipt records the ask; the execution gets its own receipt in the desk.
     */
    private Outcome fileForApproval(JsonNode action, JsonNode cap, Map<String, String> pathVars, JsonNode body,
            Map<String, String> inputs, Caller caller, Check check, Draft out) {
        String name = action.path("action").asString();
        Registry.Layer layer = registry.forTenant(caller.tenant());
        JsonNode filer = layer.capabilities().get("workforce.fileApproval");
        String path = fillPath(cap.path("route").path("path").asString(), pathVars);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "ontology." + name);
        request.put("method", cap.path("route").path("method").asString());
        request.put("path", path);
        request.put("body", json.convertValue(body, Map.class));
        request.put("reason", name + " asked by " + caller.subject() + " (" + (caller.isCustomer() ? "customer" : "staff")
                + ") with " + inputs + " — above the threshold of " + action.path("governance").path("approvalAbove").path("amount").asString("?")
                + "; needs " + action.path("governance").path("approverRole").asString("an approver"));
        ComponentClient.Reply reply = filer == null ? null
                : client.callAsMachine(filer.path("component").asString(), "POST", filer.path("route").path("path").asString(), Map.of(), request, Map.of());
        String decisionId = receipt(action, inputs, caller, check, null, "filed-for-approval",
                "above the threshold — filed for " + action.path("governance").path("approverRole").asString("an approver"));
        out.done = false;
        out.filed = reply != null && reply.ok();
        out.decisionId = decisionId;
        if (reply != null && reply.ok()) {
            out.approvalId = reply.body().path("id").asString();
            out.said = "Filed for approval: " + name + " is above the threshold this caller may decide alone; someone holding "
                    + action.path("governance").path("approverRole").asString("the approver role")
                    + " decides it under AI & Automation › Workforce and it executes with their token.";
            return new Outcome(false, 202, out.receipt());
        }
        return out.refused(503, "needs a human approver and the approval desk did not take the request ("
                + (reply == null ? "no filing capability" : Resolver.statusWords(reply)) + ")");
    }

    /* ------------------------------------------------------------------ the request */

    /** A placeholder that names an input the caller left out: the key is dropped, not sent empty. */
    private static final JsonNode OMIT = StringNode.valueOf("\u0000omit");

    private JsonNode fill(JsonNode node, JsonNode action, Map<String, String> inputs, Resolver.Resolved r, List<String> missing) {
        if (node.isTextual()) {
            String s = node.asString();
            if (s.startsWith("${") && s.endsWith("}")) {
                String path = s.substring(2, s.length() - 1);
                String v = lookup(path, inputs, r);
                JsonNode declared = inputNamed(action, path);
                if (v == null || (declared != null && v.isBlank())) {
                    if (declared != null && !declared.path("required").asBoolean(false)) {
                        return OMIT;
                    }
                    missing.add(path + " is not known");
                    return StringNode.valueOf("");
                }
                if (declared != null) {
                    switch (declared.path("type").asString()) {
                        case "number", "money" -> {
                            try {
                                return json.getNodeFactory().numberNode(new java.math.BigDecimal(v));
                            } catch (NumberFormatException e) {
                                missing.add(path + " is not a number");
                                return StringNode.valueOf(v);
                            }
                        }
                        case "boolean" -> {
                            return json.getNodeFactory().booleanNode(Boolean.parseBoolean(v));
                        }
                        default -> {
                            return StringNode.valueOf(v);
                        }
                    }
                }
                return StringNode.valueOf(v);
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode o = json.createObjectNode();
            node.properties().forEach(f -> {
                JsonNode v = fill(f.getValue(), action, inputs, r, missing);
                if (v != OMIT) {
                    o.set(f.getKey(), v);
                }
            });
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = json.createArrayNode();
            node.forEach(n -> {
                JsonNode v = fill(n, action, inputs, r, missing);
                if (v != OMIT) {
                    a.add(v);
                }
            });
            return a;
        }
        return node;
    }

    private static JsonNode inputNamed(JsonNode action, String name) {
        for (JsonNode in : action.path("inputs")) {
            if (in.path("name").asString().equals(name)) {
                return in;
            }
        }
        return null;
    }

    private static String strip(String placeholder) {
        return placeholder.startsWith("${") && placeholder.endsWith("}") ? placeholder.substring(2, placeholder.length() - 1) : placeholder;
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
            return obj.isValueNode() ? obj.asString() : null;
        }
        JsonNode v = obj;
        for (String seg : path.substring(dot + 1).split("\\.")) {
            v = v.path(seg);
        }
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static String componentWords(ComponentClient.Reply reply) {
        JsonNode b = reply.body();
        for (String k : new String[] {"message", "detail", "reason", "error"}) {
            if (b.has(k) && !b.path(k).asString().isBlank()) {
                return b.path(k).asString();
            }
        }
        return Resolver.statusWords(reply);
    }

    private String said(JsonNode action, Check check, JsonNode result, JsonNode cap) {
        JsonNode target = check.resolved().has("targetOffering") ? check.resolved().get("targetOffering")
                : check.resolved().get("targetOfferingAnyChannel");
        JsonNode sub = check.resolved().get("subscription");
        StringBuilder sb = new StringBuilder();
        sb.append("Done: ").append(action.path("action").asString());
        if (sub != null && target != null) {
            sb.append(" — \"").append(sub.path("name").asString()).append("\" becomes \"").append(target.path("name").asString()).append("\"");
        }
        sb.append(". ").append(check.preconditions().size()).append(" conditions held");
        long unknown = check.preconditions().stream().filter(v -> v.ok() == null).count();
        if (unknown > 0) {
            sb.append(" (").append(unknown).append(" could not be checked here and were left to the component)");
        }
        sb.append("; permission ").append(check.permission().by()).append("; policy ").append(check.policy().decision()).append(". ");
        sb.append("Executed by ").append(cap.path("component").asString()).append(" as order ")
                .append(result.path("id").asString("?")).append(" in state ").append(result.path("state").asString("?")).append(". ");
        sb.append("What follows: ");
        List<String> effects = new ArrayList<>();
        for (JsonNode e : action.path("effects")) {
            effects.add(e.path("capability").asString() + (e.has("when") ? " (" + e.path("when").asString() + ")" : ""));
        }
        sb.append(String.join(", ", effects)).append(".");
        return sb.toString();
    }

    /* ------------------------------------------------------------------ the receipt */

    private String receipt(JsonNode action, Map<String, String> inputs, Caller caller, Check check,
            JsonNode result, String outcome, String why) {
        String decisionId = "ontology-" + UUID.randomUUID();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decisionId", decisionId);
        d.put("decisionPoint", "ontology." + action.path("action").asString());
        d.put("subjectType", action.path("concept").asString().toLowerCase());
        d.put("subjectId", inputs.getOrDefault("subscriptionId", inputs.values().stream().findFirst().orElse("")));
        List<String> candidates = new ArrayList<>();
        try {
            for (UpgradeOption u : upgrades.availableUpgrades(check.resolved(), caller)) {
                candidates.add(String.valueOf(u.id()));
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
        for (Verdict v : check.preconditions()) {
            if (v.ok() == null) {
                constraints.add("unchecked: " + v.id() + (v.detail() == null ? "" : " — " + v.detail()));
            }
            if (Boolean.FALSE.equals(v.ok())) {
                constraints.add("failed: " + v.id() + (v.detail() == null ? "" : " — " + v.detail()));
            }
        }
        d.put("constraints", constraints);
        d.put("action", "executed".equals(outcome) ? (chosen.isEmpty() ? "executed" : chosen) : outcome);
        d.put("propensity", null);
        d.put("policy", "operational-semantic-registry");
        d.put("policyVersion", String.valueOf(action.path("version").asInt(1)));
        d.put("reason", "executed".equals(outcome)
                ? "every precondition of " + action.path("action").asString() + " held, permission " + check.permission().by()
                        + ", policy " + check.policy().decision() + "; order " + (result == null ? "?" : result.path("id").asString("?"))
                : "refused: " + why);
        Map<String, Object> context = new LinkedHashMap<>(inputs);
        context.put("channel", caller.channel());
        context.put("callerKind", caller.isCustomer() ? "customer" : "staff");
        context.put("agent", agentLabel(caller));
        d.put("context", context);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("preconditions", check.preconditions());
        evidence.put("permission", check.permission());
        evidence.put("policy", check.policy());
        d.put("evidence", evidence);
        d.put("autonomy", action.path("governance").path("autonomy").asString("medium"));
        d.put("fallback", false);
        d.put("source", "ontology");
        d.put("contract", null);
        d.put("decidedAt", OffsetDateTime.now().toString());
        d.put("@type", "Decision");
        receipts.decision(caller.tenant(), d);
        if ("executed".equals(outcome) && result != null && "completed".equalsIgnoreCase(result.path("state").asString())) {
            receipts.outcome(caller.tenant(), decisionId, "completed", result.path("id").asString());
        }
        return decisionId;
    }
    /** The agent in the receipt: a registered name, "<name> (unregistered)" when the header names one the registry does not know, "none" for a person at a screen. */
    static String agentLabel(Registry registry, Caller caller) {
        if (caller.agent() == null) {
            return "none";
        }
        return registry.forTenant(caller.tenant()).agents().containsKey(caller.agent()) ? caller.agent() : caller.agent() + " (unregistered)";
    }

    private String agentLabel(Caller caller) {
        return agentLabel(registry, caller);
    }
}
