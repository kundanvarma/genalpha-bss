package com.bss.ontology.registry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Operational Semantic Registry, loaded from the repo's ontology/ directory
 * (packaged into the jar, or ONTOLOGY_DIR on disk for development). Every
 * document is checked against its schema and every reference is resolved at
 * load: an action that names a capability, concept, component or event the
 * registry does not know fails startup, so the running service never serves a
 * definition that points at nothing.
 *
 * Three layers: TM Forum lineage is text on every concept and capability; the
 * core is the product's; a tenant overlay (ontology/tenants/&lt;tenant&gt;/…) may
 * add concepts and actions, and may tighten governance, policy and wording on a
 * core action — it may never remove a core precondition.
 */
@Component
public class Registry {

    private static final Logger log = LoggerFactory.getLogger(Registry.class);
    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** keys a tenant overlay may replace on a core action; preconditions are appended,
     *  governance merges tighten-only, everything else is core-owned */
    private static final Set<String> OVERLAY_MAY_REPLACE = Set.of("meaning", "intent", "pages", "version", "introduced");

    public record Layer(Map<String, JsonNode> concepts, Map<String, JsonNode> actions,
            Map<String, JsonNode> capabilities, Map<String, JsonNode> components, Map<String, JsonNode> agents) {
        static Layer empty() {
            return new Layer(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        }
    }

    private final String dir;
    private final Map<String, JsonNode> schemas = new LinkedHashMap<>();
    private Layer core = Layer.empty();
    private final Map<String, Layer> tenantOverlays = new LinkedHashMap<>();
    private final Map<String, Layer> merged = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    public Registry(@Value("${ontology.dir:}") String dir) throws IOException {
        this.dir = dir == null ? "" : dir.trim();
        load();
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("ontology: {}", p));
            throw new IllegalStateException("the ontology registry does not validate: " + problems.size() + " problem(s); first: " + problems.get(0));
        }
        log.info("ontology: {} concepts, {} actions, {} capabilities, {} components, {} tenant overlay(s) loaded from {}",
                core.concepts().size(), core.actions().size(), core.capabilities().size(), core.components().size(),
                tenantOverlays.size(), this.dir.isEmpty() ? "classpath" : this.dir);
    }

    /* ------------------------------------------------------------------ loading */

    private void load() throws IOException {
        for (Resource r : find("schema/*.schema.json")) {
            String name = r.getFilename().replace(".schema.json", "");
            try (InputStream in = r.getInputStream()) {
                schemas.put(name, JSON.readTree(in));
            }
        }
        core = readLayer("", "core");
        // tenants are the directories under tenants/ that hold at least one document
        java.util.TreeSet<String> tenants = new java.util.TreeSet<>();
        for (String pattern : List.of("tenants/*/actions/*.yml", "tenants/*/concepts/*.yml", "tenants/*/components/*.yml", "tenants/*/agents/*.yml", "tenants/*/capabilities.yml")) {
            for (Resource r : find(pattern)) {
                String uri = r.getURI().toString();
                int i = uri.indexOf("/tenants/");
                if (i >= 0) {
                    String rest = uri.substring(i + "/tenants/".length());
                    tenants.add(rest.substring(0, rest.indexOf('/')));
                }
            }
        }
        for (String tenant : tenants) {
            tenantOverlays.put(tenant, readLayer("tenants/" + tenant + "/", "tenant " + tenant));
        }
        crossCheck(core, "core");
        for (Map.Entry<String, Layer> en : tenantOverlays.entrySet()) {
            crossCheck(merge(core, en.getValue(), en.getKey()), "tenant " + en.getKey());
        }
    }

    private Layer readLayer(String prefix, String label) throws IOException {
        Layer layer = Layer.empty();
        for (Resource r : find(prefix + "concepts/*.yml")) {
            put(layer.concepts(), "concept", read(r), r.getFilename(), label);
        }
        for (Resource r : find(prefix + "actions/*.yml")) {
            put(layer.actions(), "action", read(r), r.getFilename(), label);
        }
        for (Resource r : find(prefix + "components/*.yml")) {
            put(layer.components(), "component", read(r), r.getFilename(), label);
        }
        for (Resource r : find(prefix + "agents/*.yml")) {
            put(layer.agents(), "agent", read(r), r.getFilename(), label);
        }
        for (Resource r : find(prefix + "capabilities.yml")) {
            JsonNode doc = read(r);
            problems.addAll(SchemaCheck.validate(schemas.get("capabilities"), doc, label + "/capabilities.yml"));
            for (JsonNode c : doc.path("capabilities")) {
                layer.capabilities().put(c.path("id").asString(), c);
            }
        }
        return layer;
    }

    private void put(Map<String, JsonNode> into, String kind, JsonNode doc, String file, String label) {
        JsonNode schema = schemas.get(kind);
        if ("action".equals(kind) && label.startsWith("tenant") && !doc.has("executes")) {
            // a tenant overlay on a core action is partial by design: only the name is required,
            // the shape of what it carries is still the action's. Governance is partial for the
            // same reason and with more force — an overlay should be able to lower one threshold
            // without restating the block, because restating it is how a guard gets dropped by
            // accident. What each key may be is still checked; whether it is present is not.
            ObjectNode partial = (ObjectNode) schema.deepCopy();
            partial.putArray("required").add("action");
            JsonNode governance = partial.path("properties").path("governance");
            if (governance.isObject()) {
                ((ObjectNode) governance).remove("required");
            }
            schema = partial;
        }
        List<String> errs = SchemaCheck.validate(schema, doc, label + "/" + file);
        problems.addAll(errs);
        String name = doc.path(kind).asString();
        if (name.isEmpty()) {
            problems.add(label + "/" + file + ": no \"" + kind + "\" name");
            return;
        }
        if (into.containsKey(name)) {
            problems.add(label + "/" + file + ": duplicate " + kind + " \"" + name + "\"");
        }
        into.put(name, doc);
    }

    private JsonNode read(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            return YAML.readTree(in);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("ontology file " + r.getFilename() + " does not parse: " + e.getOriginalMessage(), e);
        }
    }

    private List<Resource> find(String pattern) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String base = dir.isEmpty() ? "classpath*:ontology/" : "file:" + dir.replaceAll("/+$", "") + "/";
        Resource[] found = resolver.getResources(base + pattern);
        // a literal (wildcard-free) pattern yields a resource whether or not the file exists
        List<Resource> out = new ArrayList<>();
        for (Resource r : found) {
            if (r.exists()) {
                out.add(r);
            }
        }
        out.sort((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
        return out;
    }

    /* ------------------------------------------------------------------ merging */

    private Layer merge(Layer base, Layer overlay, String tenant) {
        Layer out = new Layer(new TreeMap<>(base.concepts()), new TreeMap<>(base.actions()),
                new TreeMap<>(base.capabilities()), new TreeMap<>(base.components()), new TreeMap<>(base.agents()));
        out.concepts().putAll(overlay.concepts());
        out.capabilities().putAll(overlay.capabilities());
        out.components().putAll(overlay.components());
        out.agents().putAll(overlay.agents());
        for (Map.Entry<String, JsonNode> en : overlay.actions().entrySet()) {
            JsonNode coreAction = base.actions().get(en.getKey());
            if (coreAction == null) {
                out.actions().put(en.getKey(), en.getValue());
                continue;
            }
            ObjectNode m = (ObjectNode) coreAction.deepCopy();
            ObjectNode o = (ObjectNode) en.getValue();
            o.properties().forEach(f -> {
                String k = f.getKey();
                if ("action".equals(k)) {
                    return;
                }
                if ("preconditions".equals(k)) {
                    ArrayNode pcs = (ArrayNode) m.withArray("preconditions");
                    f.getValue().forEach(pcs::add);
                } else if ("governance".equals(k)) {
                    m.set("governance", tightenGovernance(coreAction.path("governance"),
                            f.getValue(), tenant, en.getKey()));
                } else if (OVERLAY_MAY_REPLACE.contains(k)) {
                    m.set(k, f.getValue());
                } else {
                    problems.add("tenant " + tenant + ": action \"" + en.getKey() + "\" may not override core key \"" + k + "\"");
                }
            });
            m.put("tenantExtended", true);
            out.actions().put(en.getKey(), m);
        }
        return out;
    }

    /* ------------------------------------------------------------------ governance may only tighten */

    /* An operator overlays the core ontology to make an action STRICTER for its
     * own people -- that is the whole contract, and the shipped overlay says so
     * in its own comment. Until now the merge took the overlay's governance
     * block whole, so the contract was a convention rather than a rule: an
     * overlay could drop the human approver from issuing a credit, or raise the
     * amount above which one is needed. Preconditions were already append-only,
     * so the hard ceilings held; what an overlay could remove was the second
     * pair of eyes below them.
     *
     * Now each key merges on its own, a core key survives an overlay that omits
     * it (silence cannot drop a guard), and a value that would loosen the action
     * is a load-time problem -- which refuses to start the registry rather than
     * serving a weaker rule than the core promises.
     */

    /** Least to most autonomous; an overlay may move down this list, never up. */
    private static final List<String> AUTONOMY = List.of("none", "low", "medium", "high");
    /** Least to most demanding; an overlay may move UP this list, never down. */
    private static final List<String> APPROVAL = List.of("none", "human", "two-person");
    private static final List<String> AUDIT = List.of("none", "optional", "mandatory");

    private JsonNode tightenGovernance(JsonNode core, JsonNode overlay, String tenant, String action) {
        ObjectNode out = core.isObject() ? (ObjectNode) core.deepCopy() : JSON.createObjectNode();
        if (!overlay.isObject()) {
            problems.add(said(tenant, action, "governance must be a block"));
            return out;
        }
        overlay.properties().forEach(f -> {
            String k = f.getKey();
            JsonNode was = core.path(k);
            JsonNode now = f.getValue();
            switch (k) {
                case "autonomy" -> rank(out, k, was, now, AUTONOMY, false, tenant, action);
                case "approval" -> rank(out, k, was, now, APPROVAL, true, tenant, action);
                case "audit" -> rank(out, k, was, now, AUDIT, true, tenant, action);
                case "approverRole" -> {
                    // an overlay may name its OWN approver, but not delete the gate
                    if (now.isNull() || now.asString("").isBlank()) {
                        problems.add(said(tenant, action, "may not remove approverRole \"" + was.asString() + "\""));
                    } else {
                        out.set(k, now);
                    }
                }
                // "approve above 25" is tighter than "approve above 100": lower is stricter
                case "approvalAbove" -> amount(out, k, was.path("amount"), now.path("amount"),
                        now, false, tenant, action, "approvalAbove.amount");
                case "limits" -> {
                    ObjectNode limits = was.isObject() ? (ObjectNode) was.deepCopy() : JSON.createObjectNode();
                    now.properties().forEach(l -> amount(limits, l.getKey(),
                            was.path(l.getKey()), l.getValue(), l.getValue(), false,
                            tenant, action, "limits." + l.getKey()));
                    out.set(k, limits);
                }
                // anything else in the block is descriptive, not a control
                default -> out.set(k, now);
            }
        });
        return out;
    }

    /** A value from a known ladder. {@code higherIsStricter} says which way tightens. */
    private void rank(ObjectNode out, String key, JsonNode was, JsonNode now, List<String> ladder,
            boolean higherIsStricter, String tenant, String action) {
        int before = ladder.indexOf(was.asString(""));
        int after = ladder.indexOf(now.asString(""));
        if (after < 0) {
            problems.add(said(tenant, action, key + " \"" + now.asString() + "\" is not one of " + ladder));
            return;
        }
        if (before >= 0 && (higherIsStricter ? after < before : after > before)) {
            problems.add(said(tenant, action, "may not loosen " + key + " from \""
                    + was.asString() + "\" to \"" + now.asString() + "\""));
            return;
        }
        out.set(key, now);
    }

    /** A numeric ceiling or threshold. {@code higherIsStricter} says which way tightens. */
    private void amount(ObjectNode out, String key, JsonNode was, JsonNode now, JsonNode whole,
            boolean higherIsStricter, String tenant, String action, String label) {
        java.math.BigDecimal before = decimal(was);
        java.math.BigDecimal after = decimal(now);
        if (after == null) {
            problems.add(said(tenant, action, label + " must be a number"));
            return;
        }
        if (before != null) {
            int cmp = after.compareTo(before);
            if (higherIsStricter ? cmp < 0 : cmp > 0) {
                problems.add(said(tenant, action, "may not loosen " + label
                        + " from " + before.toPlainString() + " to " + after.toPlainString()));
                return;
            }
        }
        out.set(key, whole);
    }

    private static java.math.BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(n.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String said(String tenant, String action, String what) {
        return "tenant " + tenant + ": action \"" + action + "\" " + what
                + " — a tenant overlay may tighten an action, never loosen it";
    }

    /* ------------------------------------------------------------------ referential integrity */

    private void crossCheck(Layer l, String label) {
        // an agent may only read capabilities and check/execute actions the registry knows — no rights by omission
        for (JsonNode a : l.agents().values()) {
            String n = a.path("agent").asString();
            for (JsonNode cap : a.path("reads")) {
                requireCapability(l, cap.asString(), label + " agent " + n + ".reads");
            }
            for (String kind : List.of("check", "execute")) {
                for (JsonNode act : a.path("actions").path(kind)) {
                    if (!l.actions().containsKey(act.asString())) {
                        problems.add(label + " agent " + n + ".actions." + kind + ": unknown action \"" + act.asString() + "\"");
                    }
                }
            }
            for (JsonNode act : a.path("actions").path("execute")) {
                boolean mayCheck = false;
                for (JsonNode c : a.path("actions").path("check")) {
                    mayCheck |= c.asString().equals(act.asString());
                }
                if (!mayCheck) {
                    problems.add(label + " agent " + n + ": executes " + act.asString() + " without being allowed to check it first");
                }
            }
        }
        for (JsonNode c : l.concepts().values()) {
            String n = c.path("concept").asString();
            requireCapability(l, c.path("backedBy").path("capability").asString(), label + " concept " + n + ".backedBy");
            for (JsonNode link : c.path("links")) {
                requireConcept(l, link.path("to").asString(), label + " concept " + n + " link " + link.path("name").asString());
            }
            for (JsonNode ev : c.path("events")) {
                requireComponentEvent(l, ev, label + " concept " + n);
            }
        }
        for (JsonNode a : l.actions().values()) {
            String n = a.path("action").asString();
            requireConcept(l, a.path("concept").asString(), label + " action " + n + ".concept");
            requireCapability(l, a.path("executes").path("capability").asString(), label + " action " + n + ".executes");
            for (JsonNode in : a.path("inputs")) {
                if ("ref".equals(in.path("type").asString())) {
                    requireConcept(l, in.path("concept").asString(), label + " action " + n + " input " + in.path("name").asString());
                }
            }
            for (JsonNode pc : a.path("preconditions")) {
                String check = pc.path("check").asString();
                if ("state".equals(check) || "link".equals(check)) {
                    requireConcept(l, pc.path("concept").asString(), label + " action " + n + " precondition " + pc.path("id").asString());
                    requireInput(a, pc.path("of").asString(), label + " action " + n + " precondition " + pc.path("id").asString());
                }
                if ("capability".equals(check)) {
                    requireCapability(l, pc.path("capability").asString(), label + " action " + n + " precondition " + pc.path("id").asString());
                }
                if ("input".equals(check)) {
                    requireInput(a, pc.path("of").asString(), label + " action " + n + " precondition " + pc.path("id").asString());
                }
            }
            if (a.has("policy")) {
                requireCapability(l, a.path("policy").path("capability").asString(), label + " action " + n + ".policy");
            }
            for (JsonNode ef : a.path("effects")) {
                requireCapability(l, ef.path("capability").asString(), label + " action " + n + " effect");
            }
            for (JsonNode ev : a.path("emits")) {
                requireComponentEvent(l, ev, label + " action " + n);
            }
            if ("deprecated".equals(a.path("status").asString()) && !a.has("deprecated")) {
                problems.add(label + " action " + n + ": status deprecated needs a deprecated date");
            }
            if (a.has("supersededBy") && !l.actions().containsKey(a.path("supersededBy").asString())) {
                problems.add(label + " action " + n + ": supersededBy names unknown action " + a.path("supersededBy").asString());
            }
        }
        for (JsonNode comp : l.components().values()) {
            String n = comp.path("component").asString();
            for (JsonNode c : comp.path("manages")) {
                requireConcept(l, c.asString(), label + " component " + n + ".manages");
            }
            for (JsonNode c : comp.path("capabilities")) {
                requireCapability(l, c.asString(), label + " component " + n + ".capabilities");
                JsonNode cap = l.capabilities().get(c.asString());
                if (cap != null && !n.equals(cap.path("component").asString())) {
                    problems.add(label + " component " + n + " lists capability " + c.asString() + " which belongs to " + cap.path("component").asString());
                }
            }
            for (JsonNode c : comp.path("consults")) {
                requireCapability(l, c.asString(), label + " component " + n + ".consults");
            }
        }
        for (JsonNode cap : l.capabilities().values()) {
            if (!l.components().containsKey(cap.path("component").asString())) {
                problems.add(label + " capability " + cap.path("id").asString() + ": unknown component " + cap.path("component").asString());
            }
        }
    }

    private void requireConcept(Layer l, String name, String where) {
        if (name.isEmpty() || !l.concepts().containsKey(name)) {
            problems.add(where + ": unknown concept \"" + name + "\"");
        }
    }

    private void requireCapability(Layer l, String id, String where) {
        if (id.isEmpty() || !l.capabilities().containsKey(id)) {
            problems.add(where + ": unknown capability \"" + id + "\"");
        }
    }

    private void requireInput(JsonNode action, String input, String where) {
        for (JsonNode in : action.path("inputs")) {
            if (in.path("name").asString().equals(input)) {
                return;
            }
        }
        problems.add(where + ": refers to unknown input \"" + input + "\"");
    }

    private void requireComponentEvent(Layer l, JsonNode ev, String where) {
        String comp = ev.path("component").asString();
        JsonNode c = l.components().get(comp);
        if (c == null) {
            problems.add(where + ": event " + ev.path("event").asString() + " names unknown component \"" + comp + "\"");
            return;
        }
        boolean declared = false;
        for (JsonNode e : c.path("events")) {
            if (e.asString().equals(ev.path("event").asString())) {
                declared = true;
            }
        }
        if (!declared) {
            problems.add(where + ": event " + ev.path("event").asString() + " is not declared by component " + comp);
        }
    }

    /* ------------------------------------------------------------------ reading */

    /** The merged view for a tenant: the core plus the tenant's overlay, if any. */
    public Layer forTenant(String tenant) {
        String key = tenant == null ? "" : tenant;
        return merged.computeIfAbsent(key, t -> {
            Layer o = tenantOverlays.get(t);
            return o == null ? core : merge(core, o, t);
        });
    }

    public Layer core() {
        return core;
    }

    public Set<String> tenantsWithOverlay() {
        return Collections.unmodifiableSet(tenantOverlays.keySet());
    }

    public Map<String, JsonNode> schemas() {
        return Collections.unmodifiableMap(schemas);
    }
}
