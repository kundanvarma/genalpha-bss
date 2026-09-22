package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.ConformanceResult;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public ConformanceResult component(String name, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode declared = l.components().get(name);
        if (declared == null) {
            return ConformanceResult.unknown(name, null, "the registry has no entry for this component");
        }
        ComponentClient.Reply reply = client.call(name, "GET", "/.well-known/genalpha-component.json", Map.of(), Map.of(), null, null, Map.of());
        ConformanceResult.Declared declaration = new ConformanceResult.Declared(declared.path("events"), declared.path("manages"), declared.path("capabilities"));
        if (!reply.ok()) {
            return ConformanceResult.unknown(name, declaration, "the component does not describe itself (" + Resolver.statusWords(reply) + ")");
        }
        JsonNode runtime = reply.body();
        ConformanceResult.Runtime reality = new ConformanceResult.Runtime(runtime.path("events"), runtime.path("manages"), runtime.path("routes").size());
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
        String says = ok ? "the registry and the running component agree"
                : "disagreement: " + (missingEvents.isEmpty() ? "" : "events " + missingEvents + " ")
                + (missingRoutes.isEmpty() ? "" : "routes " + missingRoutes + " ")
                + (missingManages.isEmpty() ? "" : "manages " + missingManages);
        return new ConformanceResult(name, declaration, reality, ok, missingEvents, missingRoutes, servedRoutes, missingManages, says);
    }

    public List<ConformanceResult> all(String tenant) {
        List<ConformanceResult> out = new ArrayList<>();
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
