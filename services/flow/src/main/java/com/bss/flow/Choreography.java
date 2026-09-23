package com.bss.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * eventType -&gt; components that consume it (their listeners react).
     *
     * <p>Pinned order: a {@code Map.ofEntries} re-salts its iteration order on
     * every JVM start, and this map is SERIALISED into {@code /api/graph}.
     * The order below is the one the running container was printing, read off
     * the wire rather than off the declaration.</p>
     */
    public static final Map<String, List<String>> REACTORS = reactors();

    private static Map<String, List<String>> reactors() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("ProductOrderStateChangeEvent", List.of("communication"));
        m.put("CustomerBillCreateEvent", List.of("communication", "campaign"));
        m.put("TroubleTicketStateChangeEvent", List.of("communication", "campaign"));
        m.put("ChurnRiskDetectedEvent", List.of("campaign"));
        m.put("ShoppingCartAbandonedEvent", List.of("communication", "campaign"));
        m.put("ProductOrderCreateEvent",
                List.of("communication", "campaign", "service-orchestration"));
        m.put("AppointmentCreateEvent", List.of("communication"));
        m.put("AgreementCreateEvent", List.of("campaign"));
        return Collections.unmodifiableMap(m);
    }

    /** The AI back-office agents — highlighted as autonomous actors. */
    public static final List<String> AI_AGENTS = List.of("intelligence", "campaign");

    public static List<String> reactorsFor(String eventType) {
        return REACTORS.getOrDefault(eventType, List.of());
    }
}
