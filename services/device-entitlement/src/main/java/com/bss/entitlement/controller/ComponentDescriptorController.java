package com.bss.entitlement.controller;

import com.bss.entitlement.dto.ComponentDescriptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
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
    private static final List<String> EVENTS = List.of("CompanionDeviceSubscribedEvent", "CompanionDeviceUnsubscribedEvent", "EntitlementChangedEvent", "EntitlementReconfigureRequestedEvent", "SubscriptionTransferCompletedEvent", "SubscriptionTransferRequestedEvent", "EsimProfileInstalledEvent", "EsimProfileProgressEvent");

    private final RequestMappingHandlerMapping mappings;

    public ComponentDescriptorController(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @GetMapping(WELL_KNOWN)
    public ComponentDescriptor describe() {
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
        return new ComponentDescriptor("device-entitlement",
                "Tells phones what their subscription lets them use — VoLTE, Wi-Fi calling, companion eSIMs — from the plan and the line's state.",
                List.of("Entitlement"), EVENTS, "bss.entitlement.events",
                new ArrayList<>(routes), "GenAlphaComponent");
    }
}
