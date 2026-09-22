package com.bss.billing.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.bss.billing.dto.ComponentDescriptor;

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
    private static final List<String> EVENTS = List.of("BillDistributedEvent", "BillDriftDetectedEvent", "CollectionCuredEvent", "CreditNoteIssuedEvent", "CustomerBillAttributeValueChangeEvent", "CustomerBillCreateEvent", "CustomerBillResendEvent", "CustomerBillStateChangeEvent", "DebtWrittenOffEvent", "DisputeOpenedEvent", "DisputeResolvedEvent", "DunningStepReachedEvent", "InstallmentOverdueEvent", "InstallmentPaidEvent", "InstallmentPlanBrokenEvent", "InstallmentPlanCreatedEvent", "MandateCancelledEvent", "MandateRegisteredEvent", "PromiseToPayBrokenEvent", "PromiseToPayCreatedEvent", "PromiseToPayKeptEvent", "RemittanceAppliedEvent", "ServiceRestrictedForNonPaymentEvent", "ServiceSuspendedForNonPaymentEvent", "SettlementReceivedEvent");

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
        return new ComponentDescriptor("billing",
                "Rates the month into bills — prorating a plan change at its date — and runs collections and distribution.",
                List.of("Bill"), EVENTS, "bss.billing.events", new ArrayList<>(routes), "GenAlphaComponent");
    }
}
