package com.bss.hub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Jackson, no Spring context: TMF688's bytes and the door's leniency. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final OffsetDateTime at = OffsetDateTime.parse("2026-08-28T08:35:57.372419Z");

    @Test
    void aListenerWithAFilterAndOneWithout() throws Exception {
        assertEquals("{\"id\":\"s1\",\"callback\":\"http://host.docker.internal:4599/hook\","
                + "\"eventTypes\":[\"AgreementCreateEvent\"],\"active\":false,"
                + "\"createdAt\":\"2026-08-28T08:35:57.372419Z\",\"@type\":\"Hub\"}",
                mapper.writeValueAsString(HubView.of("s1",
                        "http://host.docker.internal:4599/hook",
                        mapper.readTree("[\"AgreementCreateEvent\"]"), false, at)));
        // no usable filter: the key is absent, not null — that silence is the contract
        assertEquals("{\"id\":\"s2\",\"callback\":\"https://example.test/hook\",\"active\":true,"
                + "\"createdAt\":\"2026-08-28T08:35:57.372419Z\",\"@type\":\"Hub\"}",
                mapper.writeValueAsString(HubView.of("s2", "https://example.test/hook", null, true, at)));
        // whatever the partner posted comes back verbatim, mixed types and all
        assertTrue(mapper.writeValueAsString(HubView.of("s3", "http://x", mapper.readTree(
                "[\"SnapshotProbeEvent\",7,null,{\"a\":1}]"), true, at))
                .contains("\"eventTypes\":[\"SnapshotProbeEvent\",7,null,{\"a\":1}]"));
    }

    @Test
    void theLedgerKeepsItsErrorAndItsWrittenNullEventType() throws Exception {
        assertEquals("{\"id\":\"d1\",\"eventType\":\"AgreementCreateEvent\",\"status\":\"delivered\","
                + "\"attempts\":1,\"createdAt\":\"2026-08-28T08:35:57.372419Z\",\"@type\":\"HubDelivery\"}",
                mapper.writeValueAsString(DeliveryView.of("d1", "AgreementCreateEvent",
                        "delivered", 1, null, at)));
        assertEquals("{\"id\":\"d2\",\"eventType\":null,\"status\":\"dead\",\"attempts\":4,"
                + "\"lastError\":\"I/O error on POST request: nowhere\","
                + "\"createdAt\":\"2026-08-28T08:35:57.372419Z\",\"@type\":\"HubDelivery\"}",
                mapper.writeValueAsString(DeliveryView.of("d2", null, "dead", 4,
                        "I/O error on POST request: nowhere", at)));
    }

    @Test
    void registrationReadsTheCallbackAsLenientlyAsTheMapDid() throws Exception {
        assertNull(mapper.readValue("{}", HubRequest.class).callbackText());
        assertNull(mapper.readValue("{\"callback\":null}", HubRequest.class).callbackText());
        // a posted number became its text and still failed the http test
        assertEquals("123", mapper.readValue("{\"callback\":123}", HubRequest.class).callbackText());
        assertEquals("{href=http://example.test}",
                mapper.readValue("{\"callback\":{\"href\":\"http://example.test\"}}",
                        HubRequest.class).callbackText());
        assertEquals("http://x", mapper.readValue("{\"callback\":\"http://x\"}",
                HubRequest.class).callbackText());
    }

    @Test
    void theFilterIsKeptOnlyWhenItReallyIsANonEmptyList() throws Exception {
        assertNull(mapper.readValue("{\"callback\":\"http://x\"}", HubRequest.class).eventTypesOrNull());
        assertNull(mapper.readValue("{\"callback\":\"http://x\",\"eventTypes\":[]}",
                HubRequest.class).eventTypesOrNull());
        assertNull(mapper.readValue("{\"callback\":\"http://x\",\"eventTypes\":\"AgreementCreateEvent\"}",
                HubRequest.class).eventTypesOrNull());
        assertEquals("[\"A\",\"B\"]", mapper.readValue(
                "{\"callback\":\"http://x\",\"eventTypes\":[\"A\",\"B\"]}",
                HubRequest.class).eventTypesOrNull().toString());
        // tenant, id, active and the clock cannot ride the body at all
        HubRequest hijack = mapper.readValue("{\"callback\":\"http://x\",\"tenantId\":\"nova\","
                + "\"id\":\"hijack\",\"active\":false,\"createdAt\":\"1999-01-01T00:00:00Z\"}",
                HubRequest.class);
        assertEquals("{\"callback\":\"http://x\",\"eventTypes\":null}",
                mapper.writeValueAsString(hijack));
    }
}
