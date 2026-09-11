package com.bss.ontology.service;

import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The definitions, in words. The same sentences reach the console's ? drawer,
 * the knowledge assistant, the MCP tool descriptions and the SDK's doc
 * comments — the BSS explaining itself from the model that governs it, not
 * from prose that can drift.
 */
@Service
public class ExplainService {

    private final Registry registry;

    public ExplainService(Registry registry) {
        this.registry = registry;
    }

    public Map<String, Object> action(String name, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode a = l.actions().get(name);
        if (a == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(a.path("meaning").asText());
        if (a.has("intent")) {
            lines.add("Intent: " + a.path("intent").asText());
        }
        lines.add("It acts on a " + a.path("concept").asText() + ". Inputs: " + inputs(a) + ".");
        lines.add("Who may: " + who(a) + ".");
        List<String> pcs = new ArrayList<>();
        a.path("preconditions").forEach(p -> pcs.add(p.path("says").asText()));
        lines.add("Before it happens, every one of these must hold: " + String.join("; ", pcs) + ".");
        if (a.has("policy")) {
            lines.add("Then the business rules of the \"" + a.path("policy").path("domain").asText() + "\" domain are consulted; a matching deny stops it.");
        }
        JsonNode cap = l.capabilities().get(a.path("executes").path("capability").asText());
        lines.add("It is executed by the " + cap.path("component").asText() + " component"
                + (cap.has("tmf") ? " through " + cap.path("tmf").asText() : "")
                + (cap.has("route") ? " (" + cap.path("route").path("method").asText() + " " + cap.path("route").path("path").asText() + ")" : "") + ".");
        List<String> effects = new ArrayList<>();
        for (JsonNode e : a.path("effects")) {
            JsonNode c = l.capabilities().get(e.path("capability").asText());
            effects.add(c.path("meaning").asText() + (e.has("when") ? " (when: " + e.path("when").asText() + ")" : ""));
        }
        if (!effects.isEmpty()) {
            lines.add("What follows: " + String.join(" ", effects));
        }
        List<String> emits = new ArrayList<>();
        a.path("emits").forEach(e -> emits.add(e.path("event").asText() + " from " + e.path("component").asText()
                + (e.has("meaning") ? " (" + e.path("meaning").asText() + ")" : "")));
        lines.add("It emits: " + String.join("; ", emits) + ".");
        JsonNode g = a.path("governance");
        lines.add("Governance: autonomy " + g.path("autonomy").asText() + ", approval " + g.path("approval").asText()
                + ", audit " + g.path("audit").asText() + (g.has("limits") ? ", limits " + g.path("limits").toString() : "") + ".");
        if (a.has("outcome")) {
            lines.add("It is complete when " + a.path("outcome").path("settles").asText()
                    + (a.path("outcome").has("measured") ? "; what learning may measure: " + a.path("outcome").path("measured").asText() : "") + ".");
        }
        lines.add("Version " + a.path("version").asInt() + ", introduced " + a.path("introduced").asText()
                + ("deprecated".equals(a.path("status").asText()) ? ", deprecated " + a.path("deprecated").asText()
                + (a.has("supersededBy") ? " — use " + a.path("supersededBy").asText() : "") : "")
                + (a.path("tenantExtended").asBoolean(false) ? ". This tenant has extended it with guardrails of its own" : "") + ".");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "action");
        out.put("name", name);
        out.put("title", title(name));
        out.put("text", String.join("\n", lines));
        out.put("lines", lines);
        return out;
    }

    public Map<String, Object> concept(String name, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode c = l.concepts().get(name);
        if (c == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(c.path("meaning").asText());
        lines.add("Lineage: SID " + c.path("lineage").path("sid").asText() + "; TM Forum " + join(c.path("lineage").path("tmf"))
                + (c.path("lineage").has("oda") ? "; ODA " + c.path("lineage").path("oda").asText() : "")
                + (c.path("canonical").asBoolean(false) ? ". A canonical concept: it spans several resources" : "") + ".");
        JsonNode cap = l.capabilities().get(c.path("backedBy").path("capability").asText());
        lines.add("Held by the " + cap.path("component").asText() + " component as \"" + c.path("backedBy").path("resource").asText() + "\".");
        List<String> states = new ArrayList<>();
        for (JsonNode s : c.path("states").path("values")) {
            String m = c.path("states").path("meaning").path(s.asText()).asText("");
            states.add(s.asText() + (m.isEmpty() ? "" : " (" + m + ")"));
        }
        lines.add("States (" + c.path("states").path("field").asText() + "): " + String.join(", ", states)
                + (c.path("states").has("live") ? ". In service when " + join(c.path("states").path("live")) : "") + ".");
        List<String> links = new ArrayList<>();
        c.path("links").forEach(k -> links.add(k.path("name").asText() + " → " + k.path("to").asText()
                + (k.has("meaning") ? " (" + k.path("meaning").asText() + ")" : "")));
        if (!links.isEmpty()) {
            lines.add("Related: " + String.join("; ", links) + ".");
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode a : l.actions().values()) {
            if (name.equals(a.path("concept").asText())) {
                actions.add(a.path("action").asText() + " — " + a.path("meaning").asText());
            }
        }
        lines.add(actions.isEmpty() ? "No governed action acts on it yet." : "What can be done with it: " + String.join("; ", actions) + ".");
        List<String> events = new ArrayList<>();
        c.path("events").forEach(e -> events.add(e.path("event").asText() + (e.has("meaning") ? " (" + e.path("meaning").asText() + ")" : "")));
        if (!events.isEmpty()) {
            lines.add("Events about it: " + String.join("; ", events) + ".");
        }
        if (c.path("pages").size() > 0) {
            lines.add("Console pages: " + join(c.path("pages")) + ".");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "concept");
        out.put("name", name);
        out.put("title", name);
        out.put("text", String.join("\n", lines));
        out.put("lines", lines);
        return out;
    }

    /** What a console page manages and what can be done there — the structural half of "explain this page". */
    public Map<String, Object> page(String path, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        List<String> lines = new ArrayList<>();
        List<String> concepts = new ArrayList<>();
        for (JsonNode c : l.concepts().values()) {
            for (JsonNode p : c.path("pages")) {
                if (p.asText().equals(path)) {
                    concepts.add(c.path("concept").asText() + ": " + c.path("meaning").asText());
                }
            }
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode a : l.actions().values()) {
            for (JsonNode p : a.path("pages")) {
                if (p.asText().equals(path)) {
                    actions.add(a.path("action").asText() + ": " + a.path("meaning").asText() + " Who may: " + who(a) + ".");
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "page");
        out.put("name", path);
        out.put("known", !concepts.isEmpty() || !actions.isEmpty());
        if (concepts.isEmpty() && actions.isEmpty()) {
            lines.add("The ontology has no entry for this page yet — it is described by its help articles and the manual only.");
        } else {
            if (!concepts.isEmpty()) {
                lines.add("This page manages: " + String.join(" ", concepts));
            }
            if (!actions.isEmpty()) {
                lines.add("Actions offered here: " + String.join(" ", actions));
            }
        }
        out.put("text", String.join("\n", lines));
        out.put("lines", lines);
        return out;
    }

    /** The whole journey of one action, as the registry knows it: concept → checks → policy → component → effects → events → receipt. */
    public Map<String, Object> journey(String action, String tenant) {
        Map<String, Object> a = action(action, tenant);
        if (a == null) {
            return null;
        }
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode def = l.actions().get(action);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("concept", def.path("concept").asText(), l.concepts().get(def.path("concept").asText()).path("meaning").asText()));
        for (JsonNode p : def.path("preconditions")) {
            steps.add(step("precondition", p.path("id").asText(), p.path("says").asText()));
        }
        steps.add(step("permission", "anyOf", who(def)));
        if (def.has("policy")) {
            steps.add(step("policy", def.path("policy").path("domain").asText(), "business rules of the domain, first matching deny wins"));
        }
        JsonNode cap = l.capabilities().get(def.path("executes").path("capability").asText());
        steps.add(step("execute", cap.path("id").asText(), cap.path("component").asText() + ": " + cap.path("meaning").asText()));
        for (JsonNode e : def.path("effects")) {
            JsonNode c = l.capabilities().get(e.path("capability").asText());
            steps.add(step("effect", c.path("id").asText(), c.path("component").asText() + ": " + c.path("meaning").asText()));
        }
        for (JsonNode e : def.path("emits")) {
            steps.add(step("event", e.path("event").asText(), e.path("component").asText() + (e.has("meaning") ? ": " + e.path("meaning").asText() : "")));
        }
        steps.add(step("receipt", "ontology." + action, "a decision receipt in insight's decision log, with every verdict above as evidence"));
        a.put("steps", steps);
        return a;
    }

    private static Map<String, Object> step(String kind, String name, String says) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("says", says);
        return m;
    }

    public static String who(JsonNode a) {
        List<String> who = new ArrayList<>();
        for (JsonNode c : a.path("permissions").path("anyOf")) {
            if (c.has("self")) {
                who.add("the " + c.path("self").asText() + " themselves");
            } else if (c.has("role")) {
                who.add("anyone holding " + c.path("role").asText());
            }
        }
        return String.join(", or ", who);
    }

    private static String inputs(JsonNode a) {
        List<String> in = new ArrayList<>();
        a.path("inputs").forEach(i -> in.add(i.path("name").asText() + " (" + i.path("type").asText()
                + (i.has("concept") ? " to a " + i.path("concept").asText() : "") + ")"));
        return String.join(", ", in);
    }

    private static String join(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return String.join(", ", out);
    }

    public static String title(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char ch : camel.toCharArray()) {
            if (Character.isUpperCase(ch) && sb.length() > 0) {
                sb.append(' ').append(Character.toLowerCase(ch));
            } else {
                sb.append(sb.length() == 0 ? Character.toUpperCase(ch) : ch);
            }
        }
        return sb.toString();
    }
}
