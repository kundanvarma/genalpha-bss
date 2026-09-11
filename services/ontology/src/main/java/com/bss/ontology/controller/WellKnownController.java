package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The component describes itself (Ivan's ninth principle): what I manage, what
 * it means, what actions I support, what events I produce, how I can be
 * invoked. The routes are read from the running application, not typed, so
 * the conformance suite compares declaration (the registry) with reality (this).
 */
@RestController
public class WellKnownController {

    private final Registry registry;
    private final RequestMappingHandlerMapping mappings;

    public WellKnownController(Registry registry,
            @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.registry = registry;
        this.mappings = mappings;
    }

    @GetMapping(ApiConstants.WELL_KNOWN)
    public Map<String, Object> describe() {
        JsonNode me = registry.core().components().get("ontology");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", "ontology");
        out.put("meaning", me == null ? "" : me.path("meaning").asText());
        out.put("manages", List.of());
        List<String> caps = new ArrayList<>();
        if (me != null) {
            me.path("capabilities").forEach(c -> caps.add(c.asText()));
        }
        out.put("capabilities", caps);
        List<String> events = new ArrayList<>();
        if (me != null) {
            me.path("events").forEach(e -> events.add(e.asText()));
        }
        out.put("events", events);
        out.put("topic", me == null ? "" : me.path("topic").asText());
        TreeSet<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var p : info.getPathPatternsCondition().getPatterns()) {
                String methods = info.getMethodsCondition().getMethods().isEmpty() ? "ANY" : info.getMethodsCondition().getMethods().toString();
                routes.add(methods + " " + p.getPatternString());
            }
        }
        out.put("routes", new ArrayList<>(routes));
        out.put("invoke", Map.of("read", ApiConstants.BASE_PATH, "mcp", ApiConstants.BASE_PATH + "/mcp",
                "check", ApiConstants.BASE_PATH + "/actions/{name}/check", "execute", ApiConstants.BASE_PATH + "/actions/{name}/execute"));
        out.put("@type", "GenAlphaComponent");
        return out;
    }
}
