package com.bss.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure Jackson, no Spring context. Live Flow's envelope IS the page's
 * contract — the browser reads it key by key — so these bytes are pinned,
 * and so is the graph's key order, which a {@code Map.of} had been
 * re-salting on every JVM start.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theMoveEnvelopeKeepsTheOrderTheLiveFlowPageRenders() throws Exception {
        FlowMove move = new FlowMove("e1", "2026-09-23T08:33:55.203758472Z",
                "ProductOrderCreateEvent", "product-ordering", "genalpha",
                List.of("communication", "campaign", "service-orchestration"), "party b2167f06…",
                new FlowMove.CorrelationKeys("p1", "o1", null, null, null));
        assertEquals("{\"eventId\":\"e1\",\"eventTime\":\"2026-09-23T08:33:55.203758472Z\","
                + "\"eventType\":\"ProductOrderCreateEvent\",\"source\":\"product-ordering\","
                + "\"tenant\":\"genalpha\",\"reactors\":[\"communication\",\"campaign\","
                + "\"service-orchestration\"],\"ref\":\"party b2167f06…\","
                + "\"keys\":{\"party\":\"p1\",\"order\":\"o1\"}}",
                mapper.writeValueAsString(move));
    }

    @Test
    void keysTheEventDoesNotCarryAreLeftOffAndNoKeysAtAllIsAnEmptyObject() throws Exception {
        assertEquals("{}", mapper.writeValueAsString(FlowMove.CorrelationKeys.NONE));
        assertEquals("{\"order\":\"o1\"}", mapper.writeValueAsString(
                new FlowMove.CorrelationKeys(null, "o1", null, null, null)));
        assertEquals("{\"party\":\"p1\",\"order\":\"o1\",\"intent\":\"i1\",\"quote\":\"q1\","
                + "\"object\":\"ob1\"}",
                mapper.writeValueAsString(new FlowMove.CorrelationKeys("p1", "o1", "i1", "q1", "ob1")));
        assertEquals("\"keys\":{}", mapper.writeValueAsString(
                new FlowMove("", "", "Event", "billing", "genalpha", List.of(), "",
                        FlowMove.CorrelationKeys.NONE)).replaceAll("^.*(\"keys\":\\{\\})\\}$", "$1"));
    }

    @Test
    void theGraphKeepsTheKeyOrderTheWireAlreadyHad() throws Exception {
        String json = mapper.writeValueAsString(GraphView.current());
        assertEquals("{\"consumers\":[\"communication\",\"campaign\",\"service-orchestration\"],"
                + "\"aiAgents\":[\"intelligence\",\"campaign\"],"
                + "\"reactors\":{"
                + "\"ProductOrderStateChangeEvent\":[\"communication\"],"
                + "\"CustomerBillCreateEvent\":[\"communication\",\"campaign\"],"
                + "\"TroubleTicketStateChangeEvent\":[\"communication\",\"campaign\"],"
                + "\"ChurnRiskDetectedEvent\":[\"campaign\"],"
                + "\"ShoppingCartAbandonedEvent\":[\"communication\",\"campaign\"],"
                + "\"ProductOrderCreateEvent\":[\"communication\",\"campaign\",\"service-orchestration\"],"
                + "\"AppointmentCreateEvent\":[\"communication\"],"
                + "\"AgreementCreateEvent\":[\"campaign\"]},"
                + "\"producers\":[\"product-ordering\",\"billing\",\"trouble-ticket\","
                + "\"shopping-cart\",\"agreement\",\"appointment\",\"intelligence\","
                + "\"service-orchestration\",\"payment\",\"quote\",\"assurance\"]}",
                json);
        // the lookup the listener does must still answer over the pinned map
        assertEquals(List.of("communication", "campaign", "service-orchestration"),
                Choreography.reactorsFor("ProductOrderCreateEvent"));
        assertEquals(List.of(), Choreography.reactorsFor("NoSuchEvent"));
    }
}
