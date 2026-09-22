package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.ComponentDescriptor;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
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
    public ComponentDescriptor describe() {
        JsonNode me = registry.core().components().get("ontology");
        List<String> caps = new ArrayList<>();
        List<String> events = new ArrayList<>();
        if (me != null) {
            me.path("capabilities").forEach(c -> caps.add(c.asText()));
            me.path("events").forEach(e -> events.add(e.asText()));
        }
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
        return new ComponentDescriptor("ontology", me == null ? "" : me.path("meaning").asText(), List.of(), caps, events,
                me == null ? "" : me.path("topic").asText(), new ArrayList<>(routes),
                new ComponentDescriptor.Invoke(ApiConstants.BASE_PATH, ApiConstants.BASE_PATH + "/mcp",
                        ApiConstants.BASE_PATH + "/actions/{name}/check", ApiConstants.BASE_PATH + "/actions/{name}/execute"),
                "GenAlphaComponent");
    }
}
