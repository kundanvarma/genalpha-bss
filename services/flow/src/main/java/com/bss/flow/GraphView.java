package com.bss.flow;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The static choreography the Live Flow page draws its graph from.
 *
 * <p>The key order is pinned to what the wire already carries — a
 * {@code Map.of} re-salts its iteration order on every JVM start, so this
 * order was read off the running container, not off the declaration.</p>
 */
@JsonPropertyOrder({"consumers", "aiAgents", "reactors", "producers"})
public record GraphView(
        List<String> consumers,
        List<String> aiAgents,
        Map<String, List<String>> reactors,
        List<String> producers) {

    private static final List<String> PRODUCERS = List.of("product-ordering", "billing",
            "trouble-ticket", "shopping-cart", "agreement", "appointment", "intelligence",
            "service-orchestration", "payment", "quote", "assurance");
    private static final List<String> CONSUMERS =
            List.of("communication", "campaign", "service-orchestration");

    public static GraphView current() {
        return new GraphView(CONSUMERS, Choreography.AI_AGENTS, Choreography.REACTORS, PRODUCERS);
    }
}
