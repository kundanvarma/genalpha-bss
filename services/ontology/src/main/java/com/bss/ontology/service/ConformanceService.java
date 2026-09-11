package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declaration against reality: the registry's entry for a component compared
 * with what the running component says about itself at
 * /.well-known/genalpha-component.json — every event the registry declares must
 * be one the component declares it produces, and every capability route the
 * registry maps onto it must be a route the component actually serves.
 */
@Service
public class ConformanceService {

    private final Registry registry;
    private final ComponentClient client;

    public ConformanceService(Registry registry, ComponentClient client) {
        this.registry = registry;
        this.client = client;
    }

    public Map<String, Object> component(String name, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode declared = l.components().get(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", name);
        if (declared == null) {
            out.put("ok", false);
            out.put("says", "the registry has no entry for this component");
            return out;
        }
        ComponentClient.Reply reply = client.call(name, "GET", "/.well-known/genalpha-component.json", Map.of(), Map.of(), null, null, Map.of());
        out.put("declared", Map.of("events", declared.path("events"), "manages", declared.path("manages"), "capabilities", declared.path("capabilities")));
        if (!reply.ok()) {
            out.put("ok", false);
            out.put("says", "the component does not describe itself (" + Resolver.statusWords(reply) + ")");
            return out;
        }
        JsonNode runtime = reply.body();
        out.put("runtime", Map.of("events", runtime.path("events"), "manages", runtime.path("manages"), "routes", runtime.path("routes").size()));
        List<String> missingEvents = new ArrayList<>();
        for (JsonNode ev : declared.path("events")) {
            boolean found = false;
            for (JsonNode r : runtime.path("events")) {
                if (r.asText().equals(ev.asText())) {
                    found = true;
                }
            }
            if (!found) {
                missingEvents.add(ev.asText());
            }
        }
        List<String> missingRoutes = new ArrayList<>();
        List<String> servedRoutes = new ArrayList<>();
        for (JsonNode capId : declared.path("capabilities")) {
            JsonNode cap = l.capabilities().get(capId.asText());
            if (cap == null || !cap.has("route")) {
                continue;
            }
            String path = cap.path("route").path("path").asText();
            String method = cap.path("route").path("method").asText();
            boolean served = false;
            for (JsonNode r : runtime.path("routes")) {
                String route = r.asText();
                int sp = route.indexOf(' ');
                String methods = route.substring(0, sp);
                String pattern = route.substring(sp + 1);
                if ((methods.contains(method) || "ANY".equals(methods)) && matches(pattern, path)) {
                    served = true;
                }
            }
            (served ? servedRoutes : missingRoutes).add(method + " " + path);
        }
        List<String> missingManages = new ArrayList<>();
        for (JsonNode m : declared.path("manages")) {
            boolean found = false;
            for (JsonNode r : runtime.path("manages")) {
                if (r.asText().equals(m.asText())) {
                    found = true;
                }
            }
            if (!found) {
                missingManages.add(m.asText());
            }
        }
        boolean ok = missingEvents.isEmpty() && missingRoutes.isEmpty() && missingManages.isEmpty();
        out.put("ok", ok);
        out.put("missingEvents", missingEvents);
        out.put("missingRoutes", missingRoutes);
        out.put("servedRoutes", servedRoutes);
        out.put("missingManages", missingManages);
        out.put("says", ok ? "the registry and the running component agree"
                : "disagreement: " + (missingEvents.isEmpty() ? "" : "events " + missingEvents + " ")
                + (missingRoutes.isEmpty() ? "" : "routes " + missingRoutes + " ")
                + (missingManages.isEmpty() ? "" : "manages " + missingManages));
        return out;
    }

    public List<Map<String, Object>> all(String tenant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : registry.forTenant(tenant).components().keySet()) {
            out.add(component(name, tenant));
        }
        return out;
    }

    /** Spring path patterns ({id}, {name}) against a registry route that carries the same placeholders. */
    static boolean matches(String pattern, String path) {
        if (pattern.equals(path)) {
            return true;
        }
        String p = pattern.replaceAll("\\{[^}]*\\}", "{}");
        String q = path.replaceAll("\\{[^}]*\\}", "{}");
        return p.equals(q);
    }
}
