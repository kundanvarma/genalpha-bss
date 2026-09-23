package com.bss.flow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One move on the board: an envelope off the bus, compacted to what a
 * watching browser needs — who produced it, who reacts, the tenant, a
 * masked reference, and the keys the page threads instances with. Never
 * the full payload: this is an observability surface, not a data tap.
 *
 * <p>The key order here IS the Live Flow page's contract, so it is pinned
 * and must not be reordered.</p>
 */
@JsonPropertyOrder({"eventId", "eventTime", "eventType", "source", "tenant",
        "reactors", "ref", "keys"})
public record FlowMove(
        String eventId,
        String eventTime,
        String eventType,
        String source,
        String tenant,
        List<String> reactors,
        String ref,
        CorrelationKeys keys) {

    /**
     * Correlation keys let the Process view thread events into the same
     * running instance — a party, an order, an intent flowing through stages.
     * The order↔party link comes from ProductOrderCreateEvent (it carries
     * both); service-order events carry only the order id, notifications only
     * the party — key intersection stitches them back together.
     *
     * <p>A key the event does not carry is left off, exactly as the map that
     * never put it. An event with no keys at all serialises as {@code {}}.</p>
     */
    @JsonPropertyOrder({"party", "order", "intent", "quote", "object"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorrelationKeys(String party, String order, String intent,
            String quote, String object) {

        public static final CorrelationKeys NONE = new CorrelationKeys(null, null, null, null, null);
    }
}
