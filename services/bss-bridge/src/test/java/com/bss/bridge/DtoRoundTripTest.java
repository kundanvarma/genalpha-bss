package com.bss.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure Jackson, no Spring context: the two receipts the bridge answers, and
 * the dot-path leniency the foreign document has always been read with.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theTwoReceiptsKeepTheKeyOrderTheWireAlreadyHad() throws Exception {
        assertEquals("{\"eventType\":\"IndividualCreateEvent\",\"status\":\"forwarded\","
                + "\"topic\":\"bss.bridge.events\",\"tenantId\":\"genalpha\"}",
                mapper.writeValueAsString(BridgeReceipt.Forwarded.of("IndividualCreateEvent",
                        "bss.bridge.events", "genalpha")));
        assertEquals("{\"reason\":\"unmapped foreign event type 'WHATEVER'\",\"status\":\"ignored\"}",
                mapper.writeValueAsString(BridgeReceipt.Ignored.unmapped("WHATEVER")));
        // a body with no type at all names the literal the dot-path minted
        assertEquals("{\"reason\":\"unmapped foreign event type 'null'\",\"status\":\"ignored\"}",
                mapper.writeValueAsString(BridgeReceipt.Ignored.unmapped("null")));
    }

    @Test
    void theDotPathWalksOnlyObjectsAndStopsWhereTheNestedMapStopped() throws Exception {
        JsonNode doc = mapper.readTree("{\"account\":{\"ref\":\"r1\",\"mail\":null},"
                + "\"kind\":7,\"lines\":[{\"sku\":\"P\"}],\"flat\":\"text\"}");
        assertEquals("r1", Json.valueOf(BridgeService.path(doc, "account.ref")));
        assertEquals("7", Json.valueOf(BridgeService.path(doc, "kind")));
        // a key present with a JSON null is ABSENT to the map's get(), and stays so
        assertTrue(Json.absent(BridgeService.path(doc, "account.mail")));
        assertEquals("null", Json.valueOf(BridgeService.path(doc, "account.missing")));
        assertEquals("null", Json.valueOf(BridgeService.path(doc, "nothing.at.all")));
        // a segment that is not an object ends the walk
        assertNull(BridgeService.path(doc, "flat.deeper"));
        assertNull(BridgeService.path(doc, "lines.sku"));
        assertNull(BridgeService.path(doc, null));
        // a whole block printed as a type reads as Java, not JSON — as before
        assertEquals("{a=1}", Json.valueOf(mapper.readTree("{\"a\":1}")));
    }
}
