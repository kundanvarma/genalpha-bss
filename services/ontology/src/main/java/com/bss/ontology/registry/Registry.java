package com.bss.ontology.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    /** keys a tenant overlay may replace on a core action; preconditions are appended, everything else is core-owned */
    private static final Set<String> OVERLAY_MAY_REPLACE = Set.of("meaning", "intent", "governance", "policy", "pages", "version", "introduced");

    public record Layer(Map<String, JsonNode> concepts, Map<String, JsonNode> actions,
            Map<String, JsonNode> capabilities, Map<String, JsonNode> components) {
        static Layer empty() {
            return new Layer(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
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
        for (String pattern : List.of("tenants/*/actions/*.yml", "tenants/*/concepts/*.yml", "tenants/*/components/*.yml", "tenants/*/capabilities.yml")) {
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
        for (Resource r : find(prefix + "capabilities.yml")) {
            JsonNode doc = read(r);
            problems.addAll(SchemaCheck.validate(schemas.get("capabilities"), doc, label + "/capabilities.yml"));
            for (JsonNode c : doc.path("capabilities")) {
                layer.capabilities().put(c.path("id").asText(), c);
            }
        }
        return layer;
    }

    private void put(Map<String, JsonNode> into, String kind, JsonNode doc, String file, String label) {
        JsonNode schema = schemas.get(kind);
        if ("action".equals(kind) && label.startsWith("tenant") && !doc.has("executes")) {
            // a tenant overlay on a core action is partial by design: only the name is required,
            // the shape of what it carries is still the action's
            ObjectNode partial = schema.deepCopy();
            partial.putArray("required").add("action");
            schema = partial;
        }
        List<String> errs = SchemaCheck.validate(schema, doc, label + "/" + file);
        problems.addAll(errs);
        String name = doc.path(kind).asText();
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
        } catch (com.fasterxml.jackson.core.JacksonException e) {
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
                new TreeMap<>(base.capabilities()), new TreeMap<>(base.components()));
        out.concepts().putAll(overlay.concepts());
        out.capabilities().putAll(overlay.capabilities());
        out.components().putAll(overlay.components());
        for (Map.Entry<String, JsonNode> en : overlay.actions().entrySet()) {
            JsonNode coreAction = base.actions().get(en.getKey());
            if (coreAction == null) {
                out.actions().put(en.getKey(), en.getValue());
                continue;
            }
            ObjectNode m = coreAction.deepCopy();
            ObjectNode o = (ObjectNode) en.getValue();
            o.fields().forEachRemaining(f -> {
                String k = f.getKey();
                if ("action".equals(k)) {
                    return;
                }
                if ("preconditions".equals(k)) {
                    ArrayNode pcs = (ArrayNode) m.withArray("preconditions");
                    f.getValue().forEach(pcs::add);
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

    /* ------------------------------------------------------------------ referential integrity */

    private void crossCheck(Layer l, String label) {
        for (JsonNode c : l.concepts().values()) {
            String n = c.path("concept").asText();
            requireCapability(l, c.path("backedBy").path("capability").asText(), label + " concept " + n + ".backedBy");
            for (JsonNode link : c.path("links")) {
                requireConcept(l, link.path("to").asText(), label + " concept " + n + " link " + link.path("name").asText());
            }
            for (JsonNode ev : c.path("events")) {
                requireComponentEvent(l, ev, label + " concept " + n);
            }
        }
        for (JsonNode a : l.actions().values()) {
            String n = a.path("action").asText();
            requireConcept(l, a.path("concept").asText(), label + " action " + n + ".concept");
            requireCapability(l, a.path("executes").path("capability").asText(), label + " action " + n + ".executes");
            for (JsonNode in : a.path("inputs")) {
                if ("ref".equals(in.path("type").asText())) {
                    requireConcept(l, in.path("concept").asText(), label + " action " + n + " input " + in.path("name").asText());
                }
            }
            for (JsonNode pc : a.path("preconditions")) {
                String check = pc.path("check").asText();
                if ("state".equals(check) || "link".equals(check)) {
                    requireConcept(l, pc.path("concept").asText(), label + " action " + n + " precondition " + pc.path("id").asText());
                    requireInput(a, pc.path("of").asText(), label + " action " + n + " precondition " + pc.path("id").asText());
                }
                if ("capability".equals(check)) {
                    requireCapability(l, pc.path("capability").asText(), label + " action " + n + " precondition " + pc.path("id").asText());
                }
                if ("input".equals(check)) {
                    requireInput(a, pc.path("of").asText(), label + " action " + n + " precondition " + pc.path("id").asText());
                }
            }
            if (a.has("policy")) {
                requireCapability(l, a.path("policy").path("capability").asText(), label + " action " + n + ".policy");
            }
            for (JsonNode ef : a.path("effects")) {
                requireCapability(l, ef.path("capability").asText(), label + " action " + n + " effect");
            }
            for (JsonNode ev : a.path("emits")) {
                requireComponentEvent(l, ev, label + " action " + n);
            }
            if ("deprecated".equals(a.path("status").asText()) && !a.has("deprecated")) {
                problems.add(label + " action " + n + ": status deprecated needs a deprecated date");
            }
            if (a.has("supersededBy") && !l.actions().containsKey(a.path("supersededBy").asText())) {
                problems.add(label + " action " + n + ": supersededBy names unknown action " + a.path("supersededBy").asText());
            }
        }
        for (JsonNode comp : l.components().values()) {
            String n = comp.path("component").asText();
            for (JsonNode c : comp.path("manages")) {
                requireConcept(l, c.asText(), label + " component " + n + ".manages");
            }
            for (JsonNode c : comp.path("capabilities")) {
                requireCapability(l, c.asText(), label + " component " + n + ".capabilities");
                JsonNode cap = l.capabilities().get(c.asText());
                if (cap != null && !n.equals(cap.path("component").asText())) {
                    problems.add(label + " component " + n + " lists capability " + c.asText() + " which belongs to " + cap.path("component").asText());
                }
            }
            for (JsonNode c : comp.path("consults")) {
                requireCapability(l, c.asText(), label + " component " + n + ".consults");
            }
        }
        for (JsonNode cap : l.capabilities().values()) {
            if (!l.components().containsKey(cap.path("component").asText())) {
                problems.add(label + " capability " + cap.path("id").asText() + ": unknown component " + cap.path("component").asText());
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
            if (in.path("name").asText().equals(input)) {
                return;
            }
        }
        problems.add(where + ": refers to unknown input \"" + input + "\"");
    }

    private void requireComponentEvent(Layer l, JsonNode ev, String where) {
        String comp = ev.path("component").asText();
        JsonNode c = l.components().get(comp);
        if (c == null) {
            problems.add(where + ": event " + ev.path("event").asText() + " names unknown component \"" + comp + "\"");
            return;
        }
        boolean declared = false;
        for (JsonNode e : c.path("events")) {
            if (e.asText().equals(ev.path("event").asText())) {
                declared = true;
            }
        }
        if (!declared) {
            problems.add(where + ": event " + ev.path("event").asText() + " is not declared by component " + comp);
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
