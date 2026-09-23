package com.bss.process.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure Jackson, no Spring context: the bytes TMF701 puts on the wire, and
 * the leniency the request records inherited from the maps they replace.
 * Runs in seconds and fails before a container is ever built.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void specViewKeepsItsKeyOrderAndWritesNullsTheMapWrote() throws Exception {
        JsonNode tasks = mapper.readTree(
                "[{\"code\":\"a\",\"name\":\"A\",\"allowanceSeconds\":5},{\"name\":\"no code\",\"extra\":true}]");
        SpecView v = SpecView.of("order-digital", "Order to activation (digital)",
                "A digital order provisions and activates without human hands.",
                List.of(tasks.get(0), tasks.get(1)));
        assertEquals("{\"code\":\"order-digital\",\"name\":\"Order to activation (digital)\","
                + "\"description\":\"A digital order provisions and activates without human hands.\","
                + "\"taskFlowSpecification\":[{\"code\":\"a\",\"name\":\"A\",\"allowanceSeconds\":5},"
                + "{\"name\":\"no code\",\"extra\":true}],\"@type\":\"ProcessFlowSpecification\"}",
                json(v));
        // the map put() these unconditionally: a null name is a written null, not a missing key
        assertEquals("{\"code\":\"x\",\"name\":null,\"description\":null,"
                + "\"taskFlowSpecification\":[],\"@type\":\"ProcessFlowSpecification\"}",
                json(SpecView.of("x", null, null, List.of())));
    }

    @Test
    void anOrderFlowWritesProductOrderIdAndAnOfferFlowWritesProductOfferingId() throws Exception {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-23T08:23:28.0578Z");
        FlowView order = new FlowView("f1", "/tmf-api/processFlowManagement/v4/processFlow/f1",
                "order-digital", null, "o1", "completed", null,
                List.of(FlowView.PartyRef.customer("p1")), at, at,
                List.of(FlowView.TaskView.of("t1", "placed", "Order placed", "completed", 0L, null)),
                new FlowView.Summary("Completed", "Everything is done — all steps completed.",
                        false, 1, 1),
                null, "ProcessFlow");
        assertEquals("{\"id\":\"f1\",\"href\":\"/tmf-api/processFlowManagement/v4/processFlow/f1\","
                + "\"specCode\":\"order-digital\",\"productOrderId\":\"o1\",\"state\":\"completed\","
                + "\"relatedParty\":[{\"role\":\"customer\",\"id\":\"p1\"}],"
                + "\"startedAt\":\"2026-09-23T08:23:28.0578Z\",\"completedAt\":\"2026-09-23T08:23:28.0578Z\","
                + "\"taskFlow\":[{\"id\":\"t1\",\"code\":\"placed\",\"name\":\"Order placed\","
                + "\"state\":\"completed\",\"@type\":\"TaskFlow\"}],"
                + "\"summary\":{\"headline\":\"Completed\",\"why\":\"Everything is done — all steps completed.\","
                + "\"needsAttention\":false,\"stepsDone\":1,\"stepsTotal\":1},\"@type\":\"ProcessFlow\"}",
                json(order));

        FlowView offer = new FlowView("f2", "/h", "offer-launch-governance", "po1", null,
                "inProgress", "held by sigrid", null, at, null,
                List.of(FlowView.TaskView.of("t2", "approved", "Launch approval", "completed",
                        432000L, "approved by henrik")),
                new FlowView.Summary("In progress — 1 of 1 steps done", "Working on it.", false, 1, 1),
                List.of(new FlowView.TimelineEntry(at, "ProductOfferingGovernanceEvent",
                        "bss.catalog.events", "{}")),
                "ProcessFlow");
        String s = json(offer);
        assertTrue(s.contains("\"specCode\":\"offer-launch-governance\",\"productOfferingId\":\"po1\",\"state\""), s);
        assertTrue(s.contains("\"allowanceSeconds\":432000,\"message\":\"approved by henrik\""), s);
        // the timeline's own key order, read off the wire the map was printing
        assertTrue(s.contains("\"timeline\":[{\"eventTime\":\"2026-09-23T08:23:28.0578Z\","
                + "\"eventType\":\"ProductOfferingGovernanceEvent\",\"sourceTopic\":\"bss.catalog.events\","
                + "\"digest\":\"{}\"}],\"@type\":\"ProcessFlow\"}"), s);
        // no relatedParty, no completedAt: keys the map left off entirely
        assertTrue(!s.contains("relatedParty") && !s.contains("completedAt"), s);
        // allowanceSeconds <= 0 is left off, as the map left it off
        assertEquals("{\"id\":\"t\",\"code\":\"c\",\"name\":null,\"state\":\"pending\",\"@type\":\"TaskFlow\"}",
                json(FlowView.TaskView.of("t", "c", null, "pending", 0L, null)));
    }

    @Test
    void aSpecRequestReadsEveryLeafAsLenientlyAsTheMapDid() throws Exception {
        SpecRequest r = mapper.readValue(
                "{\"code\":12345,\"name\":true,\"description\":{\"a\":1},"
                + "\"taskFlowSpecification\":\"not a list\",\"tenantId\":\"nova\"}", SpecRequest.class);
        assertEquals("12345", r.codeText());
        assertEquals("true", r.nameText());
        assertEquals("{a=1}", r.descriptionText());   // String.valueOf of a nested block, as before
        assertNull(r.taskListOrNull());               // not a list: silently ignored, as before
        // absent and explicit null are one thing — the map's get() returned null for both
        assertNull(mapper.readValue("{}", SpecRequest.class).codeText());
        assertNull(mapper.readValue("{\"code\":null}", SpecRequest.class).codeText());
        assertEquals(2, mapper.readValue("{\"taskFlowSpecification\":[1,2]}", SpecRequest.class)
                .taskListOrNull().size());
    }

    @Test
    void aTaskPatchRefusesAMissingStateWithTheLiteralNullItAlwaysRefused() throws Exception {
        assertEquals("null", mapper.readValue("{}", TaskPatchRequest.class).stateText());
        assertEquals("null", mapper.readValue("{\"state\":null}", TaskPatchRequest.class).stateText());
        assertEquals("7", mapper.readValue("{\"state\":7}", TaskPatchRequest.class).stateText());
        assertEquals("completed", mapper.readValue("{\"state\":\"completed\"}", TaskPatchRequest.class).stateText());
        // and the allowed-states check must never be handed a Java null
        assertTrue(!List.of("completed", "inProgress", "failed")
                .contains(mapper.readValue("{}", TaskPatchRequest.class).stateText()));
        assertEquals("operator decision",
                mapper.readValue("{\"state\":\"failed\"}", TaskPatchRequest.class).messageOrDefault());
        assertEquals("operator decision",
                mapper.readValue("{\"state\":\"failed\",\"message\":null}", TaskPatchRequest.class).messageOrDefault());
        assertEquals("snapshot probe", mapper.readValue(
                "{\"state\":\"failed\",\"message\":\"snapshot probe\"}", TaskPatchRequest.class).messageOrDefault());
        // a body cannot carry a field the record never declared
        TaskPatchRequest hijack = mapper.readValue(
                "{\"state\":\"failed\",\"tenantId\":\"nova\",\"id\":\"hijack\"}", TaskPatchRequest.class);
        assertEquals("failed", hijack.stateText());
        assertEquals("{\"state\":\"failed\",\"message\":null}", json(hijack));
    }

    @Test
    void jsonHelperMirrorsStringValueOfOverAMap() throws Exception {
        JsonNode n = mapper.readTree("{\"s\":\"a\",\"i\":7,\"d\":1.5,\"b\":false,"
                + "\"o\":{\"noId\":\"obj-1\"},\"l\":[\"a\",\"list\"],\"z\":null}");
        assertEquals("a", Json.valueOf(n.get("s")));
        assertEquals("7", Json.valueOf(n.get("i")));
        assertEquals("1.5", Json.valueOf(n.get("d")));
        assertEquals("false", Json.valueOf(n.get("b")));
        assertEquals("{noId=obj-1}", Json.valueOf(n.get("o")));
        assertEquals("[a, list]", Json.valueOf(n.get("l")));
        assertEquals("null", Json.valueOf(n.get("z")));
        assertEquals("null", Json.valueOf(n.get("missing")));
        assertNull(Json.textOrNull(n.get("z")));
        assertNull(Json.textOrNull(n.get("missing")));
        assertTrue(Json.absent(n.get("z")) && Json.absent(null) && !Json.absent(n.get("s")));
    }
}
