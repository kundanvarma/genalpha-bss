package com.bss.som.controller;

import org.springframework.beans.factory.annotation.Qualifier;
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
 * The component describes itself (the ontology's ninth principle): what I
 * manage, what it means, what events I produce, how I can be invoked. The
 * routes are read from the running application, the rest is declared here;
 * the Operational Semantic Registry compares this with its own entry for the
 * component and the conformance suite fails on any disagreement.
 */
@RestController
public class ComponentDescriptorController {

    public static final String WELL_KNOWN = "/.well-known/genalpha-component.json";
    private static final List<String> EVENTS = List.of("IntentCreateEvent", "NumberChangedEvent", "ProviderAccessOrderStateChangeEvent", "ServiceAttributeValueChangeEvent", "ServiceOrderStateChangeEvent", "ServiceRestrictedEvent", "ServiceResumedEvent", "ServiceSliceChangeEvent", "ServiceSliceRefusedEvent", "ServiceSuspendedEvent", "ServiceTerminatedEvent", "ServiceTransferredEvent", "ServiceUnrestrictedEvent", "SimPinResetEvent", "SimReplacedEvent", "TelesalesOfferEvent", "WholesaleAccessOrderStateChangeEvent");

    private final RequestMappingHandlerMapping mappings;

    public ComponentDescriptorController(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @GetMapping(WELL_KNOWN)
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", "service-orchestration");
        out.put("meaning", "Turns product orders into live services — activates lines, drives the OCS and the entitlement server, and records the service inventory.");
        out.put("manages", List.of("Service"));
        out.put("events", EVENTS);
        out.put("topic", "bss.som.events");
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
        out.put("@type", "GenAlphaComponent");
        return out;
    }
}
