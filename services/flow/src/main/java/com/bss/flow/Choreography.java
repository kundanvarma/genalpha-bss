package com.bss.flow;

import java.util.List;
import java.util.Map;

/**
 * The editorial map of who reacts to what — mirrors the real Kafka listeners
 * (communication, campaign, service-orchestration). It turns a bare event
 * into a story: this producer emitted it, and these components consume it.
 */
public final class Choreography {

    private Choreography() {
    }

    /** eventType -> components that consume it (their listeners react). */
    public static final Map<String, List<String>> REACTORS = Map.ofEntries(
            Map.entry("ProductOrderCreateEvent",
                    List.of("communication", "campaign", "service-orchestration")),
            Map.entry("ProductOrderStateChangeEvent", List.of("communication")),
            Map.entry("CustomerBillCreateEvent", List.of("communication", "campaign")),
            Map.entry("TroubleTicketStateChangeEvent", List.of("communication", "campaign")),
            Map.entry("AppointmentCreateEvent", List.of("communication")),
            Map.entry("ShoppingCartAbandonedEvent", List.of("communication", "campaign")),
            Map.entry("AgreementCreateEvent", List.of("campaign")),
            Map.entry("ChurnRiskDetectedEvent", List.of("campaign")));

    /** The AI back-office agents — highlighted as autonomous actors. */
    public static final List<String> AI_AGENTS = List.of("intelligence", "campaign");

    public static List<String> reactorsFor(String eventType) {
        return REACTORS.getOrDefault(eventType, List.of());
    }
}
