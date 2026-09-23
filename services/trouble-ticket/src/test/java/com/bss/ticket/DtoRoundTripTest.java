package com.bss.ticket;

import com.bss.ticket.api.Json;
import com.bss.ticket.dto.OrgRef;
import com.bss.ticket.dto.PartyRef;
import com.bss.ticket.dto.TicketNote;
import com.bss.ticket.dto.TicketView;
import com.bss.ticket.dto.TroubleTicketCreateRequest;
import com.bss.ticket.dto.TroubleTicketPatchRequest;
import com.bss.ticket.entity.TroubleTicket;
import com.bss.ticket.privacy.EraseRequest;
import com.bss.ticket.privacy.PrivacyExport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TMF621 records write the bytes the maps used to write: keys in the same
 * order, the two conditional blocks absent when they were absent, the stored
 * note array re-read into records, and the map's leniency (a number for a
 * ticketType, the literal "null" for a missing id) preserved. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-23T10:00:00Z");

    private TicketView full() {
        return new TicketView("t-1", "/tmf-api/troubleTicket/v4/troubleTicket/t-1",
                "Router blinking red", "The LED is red after the storm", "major", "incident",
                "inProgress", List.of(PartyRef.customer("party-1")), OrgRef.of("genalpha-retail"),
                node("[{\"id\":\"dm-1\",\"role\":\"source\"}]"),
                List.of(new TicketNote(T.toString(), "agent-1", "Power-cycled the ONT")),
                T, T, T);
    }

    private JsonNode node(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ticketViewWritesTheKeysTheMapWroteInTheOrderTheMapWroteThem() throws Exception {
        assertEquals("{\"id\":\"t-1\",\"href\":\"/tmf-api/troubleTicket/v4/troubleTicket/t-1\","
                + "\"name\":\"Router blinking red\",\"description\":\"The LED is red after the storm\","
                + "\"severity\":\"major\",\"ticketType\":\"incident\",\"status\":\"inProgress\","
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                + "\"organization\":{\"@referredType\":\"Organization\",\"id\":\"genalpha-retail\"},"
                + "\"relatedEntity\":[{\"id\":\"dm-1\",\"role\":\"source\"}],"
                + "\"note\":[{\"date\":\"2026-09-23T10:00Z\",\"author\":\"agent-1\","
                + "\"text\":\"Power-cycled the ONT\"}],"
                + "\"creationDate\":\"2026-09-23T10:00:00Z\",\"statusChangeDate\":\"2026-09-23T10:00:00Z\","
                + "\"lastUpdate\":\"2026-09-23T10:00:00Z\",\"@type\":\"TroubleTicket\"}",
                json.writeValueAsString(full()));
    }

    @Test
    void anOwnerlessTicketLeavesTheTwoConditionalBlocksOff() throws Exception {
        TicketView bare = new TicketView("t-2", "/h", "Social care: @sam", "Social care: @sam",
                "minor", "socialCare", "acknowledged", null, OrgRef.of("genalpha-retail"), null,
                List.of(), T, T, T);
        String wire = json.writeValueAsString(bare);
        assertFalse(wire.contains("relatedParty"));
        assertFalse(wire.contains("relatedEntity"));
        assertTrue(wire.contains("\"note\":[]"));
        // every other key is written even when the map wrote it null
        TicketView clockless = new TicketView("t-3", "/h", "n", "n", null, "support", null, null,
                OrgRef.of("o"), null, List.of(), null, null, null);
        assertTrue(json.writeValueAsString(clockless).contains("\"creationDate\":null"));
        assertTrue(json.writeValueAsString(clockless).contains("\"severity\":null"));
    }

    @Test
    void storedNotesReReadIntoRecordsWhateverOrderTheirJvmWroteThem() throws Exception {
        List<TicketNote> notes = json.readValue(
                "[{\"author\":\"a-1\",\"date\":\"2026-07-10T16:54:49Z\",\"text\":\"Line test\"}]",
                json.getTypeFactory().constructCollectionType(List.class, TicketNote.class));
        assertEquals("a-1", notes.get(0).author());
        assertEquals("[{\"date\":\"2026-07-10T16:54:49Z\",\"author\":\"a-1\",\"text\":\"Line test\"}]",
                json.writeValueAsString(notes));
    }

    @Test
    void theCreateRequestKeepsTheMapsLeniency() throws Exception {
        TroubleTicketCreateRequest r = json.readValue(
                "{\"description\":\"d\",\"ticketType\":7,\"severity\":3,\"unknownHouseField\":{\"a\":1},"
                + "\"relatedParty\":[{\"role\":\"agent\"},{\"role\":\"Customer\"}]}",
                TroubleTicketCreateRequest.class);
        assertEquals("7", Json.valueOfLike(r.ticketType()));
        assertEquals("3", Json.valueOfLike(r.severity()));
        assertNull(r.name());
        // a customer reference without an id still mints the literal, as the map did
        assertEquals("null", r.customerPartyId());
    }

    @Test
    void anAbsentAndAnExplicitNullAreOneThing() throws Exception {
        TroubleTicketPatchRequest absent = json.readValue("{}", TroubleTicketPatchRequest.class);
        TroubleTicketPatchRequest explicit =
                json.readValue("{\"status\":null,\"note\":null}", TroubleTicketPatchRequest.class);
        assertFalse(Json.present(absent.status()));
        assertFalse(Json.present(explicit.status()));
        assertFalse(Json.present(explicit.note()));
        assertEquals("null", Json.valueOfLike(explicit.status()));
        // and a nested block prints Java, not JSON — the map's own rendering
        assertEquals("{a=1}", Json.valueOfLike(node("{\"a\":1}")));
        assertEquals("[a, b]", Json.valueOfLike(node("[\"a\",\"b\"]")));
    }

    @Test
    void aCustomerReferenceWithAnIdIsTheOwner() throws Exception {
        TroubleTicketCreateRequest r = json.readValue(
                "{\"relatedParty\":{\"id\":\"p-1\",\"role\":\"customer\"}}",
                TroubleTicketCreateRequest.class);
        assertNull(r.customerPartyId()); // not a list: the map ignored it too
        TroubleTicketCreateRequest listed = json.readValue(
                "{\"relatedParty\":[{\"id\":\"p-1\",\"role\":\"customer\"}]}",
                TroubleTicketCreateRequest.class);
        assertEquals("p-1", listed.customerPartyId());
    }

    @Test
    void thePrivacyShelvesKeepTheirKeyOrder() throws Exception {
        TroubleTicket row = new TroubleTicket();
        row.setId("t-1");
        assertTrue(json.writeValueAsString(PrivacyExport.of("tickets", List.of(row)))
                .startsWith("{\"category\":\"tickets\",\"count\":1,\"items\":[{\"id\":\"t-1\""));
        assertEquals("{\"category\":\"tickets\",\"count\":0,\"items\":[]}",
                json.writeValueAsString(PrivacyExport.of("tickets", List.of())));
        assertNull(json.readValue("{}", EraseRequest.class).partyId());
        assertNull(json.readValue("{\"partyId\":null}", EraseRequest.class).partyId());
    }
}
