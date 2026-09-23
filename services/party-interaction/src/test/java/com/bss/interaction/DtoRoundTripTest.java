package com.bss.interaction;

import com.bss.interaction.api.Json;
import com.bss.interaction.dto.ChannelRef;
import com.bss.interaction.dto.InteractionView;
import com.bss.interaction.dto.OrgRef;
import com.bss.interaction.dto.PartyRef;
import com.bss.interaction.entity.PartyInteraction;
import com.bss.interaction.privacy.EraseRequest;
import com.bss.interaction.privacy.PrivacyExport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TMF683 record writes what the overlay map wrote: the mandatory
 * channel/direction/reason trio on every row, the caller's own blocks
 * untouched (including an explicit null the caller posted), the posted keys
 * this view does not declare kept in the order they arrived. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-23T10:00:00Z");

    private JsonNode node(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aHouseRowCarriesTheMandatoryTrioAndTheDerivedParties() throws Exception {
        InteractionView view = new InteractionView("i-1", "/h",
                TextNode.valueOf("Message sent: Order complete"),
                json.valueToTree(List.of(new ChannelRef("inApp"))),
                TextNode.valueOf("Message sent: Order complete"), "outbound", "completed",
                TextNode.valueOf("communication"),
                json.valueToTree(List.of(PartyRef.customer("p-1"))),
                OrgRef.of("genalpha-retail"), T, T, null);
        assertEquals("{\"id\":\"i-1\",\"href\":\"/h\",\"description\":\"Message sent: Order complete\","
                + "\"channel\":[{\"name\":\"inApp\"}],\"reason\":\"Message sent: Order complete\","
                + "\"direction\":\"outbound\",\"status\":\"completed\",\"sourceSystem\":\"communication\","
                + "\"relatedParty\":[{\"id\":\"p-1\",\"@referredType\":\"Individual\",\"role\":\"customer\"}],"
                + "\"organization\":{\"@referredType\":\"Organization\",\"id\":\"genalpha-retail\"},"
                + "\"interactionDate\":\"2026-09-23T10:00:00Z\",\"lastUpdate\":\"2026-09-23T10:00:00Z\","
                + "\"@type\":\"PartyInteraction\"}",
                json.writeValueAsString(view));
    }

    @Test
    void anAgentReferenceHasNoReferredType() throws Exception {
        assertEquals("[{\"id\":\"p-1\",\"@referredType\":\"Individual\",\"role\":\"customer\"},"
                + "{\"id\":\"a-1\",\"role\":\"agent\"}]",
                json.writeValueAsString(List.of(PartyRef.customer("p-1"), PartyRef.agent("a-1"))));
    }

    @Test
    void aBlockTheCallerNeverSentIsAbsentAndOneTheySentNullIsNull() throws Exception {
        InteractionView bare = new InteractionView("i-2", "/h", null,
                json.valueToTree(List.of(new ChannelRef("assisted"))),
                TextNode.valueOf("customer interaction"), "inbound", "completed", null, null,
                OrgRef.of("o"), T, T, null);
        String wire = json.writeValueAsString(bare);
        assertFalse(wire.contains("description"));
        assertFalse(wire.contains("sourceSystem"));
        assertFalse(wire.contains("relatedParty"));

        InteractionView nulled = new InteractionView("i-3", "/h", node("null"),
                json.valueToTree(List.of(new ChannelRef("assisted"))),
                TextNode.valueOf("customer interaction"), "inbound", "completed", node("null"),
                node("null"), OrgRef.of("o"), T, T, null);
        String nulls = json.writeValueAsString(nulled);
        assertTrue(nulls.contains("\"description\":null"));
        assertTrue(nulls.contains("\"sourceSystem\":null"));
        assertTrue(nulls.contains("\"relatedParty\":null"));
    }

    @Test
    void thePostedKeysThisViewDoesNotDeclareRideAlongInOrder() throws Exception {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("interactionItem", node("[{\"id\":\"item-1\"}]"));
        extras.put("unknownHouseField", node("{\"kept\":true}"));
        InteractionView view = new InteractionView("i-4", "/h", null,
                node("5"), node("7"), "inbound", "completed", null, null, OrgRef.of("o"), T, T, extras);
        String wire = json.writeValueAsString(view);
        assertTrue(wire.endsWith("\"@type\":\"PartyInteraction\",\"interactionItem\":[{\"id\":\"item-1\"}],"
                + "\"unknownHouseField\":{\"kept\":true}}"), wire);
        // a channel that is not an array was answered as posted, and still is
        assertTrue(wire.contains("\"channel\":5"));
    }

    @Test
    void theDoorKeepsTheMapsLeniency() {
        assertFalse(Json.present(null));
        assertFalse(Json.present(node("null")));
        assertEquals("null", Json.valueOfLike(null));
        assertEquals("7", Json.valueOfLike(node("7")));
        assertEquals("[a, b]", Json.valueOfLike(node("[\"a\",\"b\"]")));
        assertEquals("{a=1}", Json.valueOfLike(node("{\"a\":1}")));
    }

    @Test
    void thePrivacyShelvesKeepTheirKeyOrder() throws Exception {
        PartyInteraction row = new PartyInteraction();
        row.setId("i-1");
        String shelf = json.writeValueAsString(PrivacyExport.of("interactions", List.of(row)));
        assertTrue(shelf.startsWith("{\"count\":1,\"category\":\"interactions\",\"items\":[{"), shelf);
        assertTrue(shelf.contains("\"id\":\"i-1\""), shelf);
        assertEquals("{\"count\":0,\"category\":\"interactions\",\"items\":[]}",
                json.writeValueAsString(PrivacyExport.of("interactions", List.of())));
        assertEquals(null, json.readValue("{}", EraseRequest.class).partyId());
        assertEquals(null, json.readValue("{\"partyId\":null}", EraseRequest.class).partyId());
    }
}
