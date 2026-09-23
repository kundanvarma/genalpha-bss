package com.bss.appointment;

import com.bss.appointment.dto.AppointmentPatch;
import com.bss.appointment.dto.AppointmentRequest;
import com.bss.appointment.dto.AppointmentView;
import com.bss.appointment.dto.EraseReceipt;
import com.bss.appointment.dto.Json;
import com.bss.appointment.dto.PartyRef;
import com.bss.appointment.dto.PrivacyExport;
import com.bss.appointment.dto.ProbeResult;
import com.bss.appointment.dto.ScheduleConfigRequest;
import com.bss.appointment.dto.ScheduleConfigView;
import com.bss.appointment.dto.SearchTimeSlotRequest;
import com.bss.appointment.dto.SearchTimeSlotResult;
import com.bss.appointment.dto.SlotView;
import com.bss.appointment.dto.TechnicianRequest;
import com.bss.appointment.dto.TechnicianView;
import com.bss.appointment.dto.TimeWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes and the key order of the installation calendar. The
 * orders pinned here are the ones the wire already had — several of them came
 * out of a {@code Map.of}, which re-salts itself on every JVM start, so they
 * were read off a snapshot of the running container, not off the source.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final OffsetDateTime CLOCK = OffsetDateTime.parse("2026-09-23T08:00:00Z");

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void anAppointmentKeepsTheKeysTheMapWroteAndLeavesOffTheFourItSkipped() throws Exception {
        assertThat(write(new AppointmentView("a-1", "/tmf-api/appointment/v4/appointment/a-1",
                "confirmed", null, new TimeWindow("2026-09-24T09:00+02:00", "2026-09-24T11:00+02:00"),
                null, null, null, null, null, CLOCK, CLOCK)))
                .isEqualTo("{\"id\":\"a-1\",\"href\":\"/tmf-api/appointment/v4/appointment/a-1\","
                        + "\"status\":\"confirmed\",\"validFor\":{\"startDateTime\":\"2026-09-24T09:00+02:00\","
                        + "\"endDateTime\":\"2026-09-24T11:00+02:00\"},\"relatedEntity\":null,\"place\":null,"
                        + "\"creationDate\":\"2026-09-23T08:00:00Z\",\"lastUpdate\":\"2026-09-23T08:00:00Z\","
                        + "\"@type\":\"Appointment\"}");
    }

    /** relatedParty printed role, @referredType, id — a Map.of order, and a contract now. */
    @Test
    void theCustomerRefKeepsTheOrderTheWireHas() throws Exception {
        assertThat(write(PartyRef.customer("p-1")))
                .isEqualTo("{\"role\":\"customer\",\"@referredType\":\"Individual\",\"id\":\"p-1\"}");
        assertThat(write(new AppointmentView("a-1", "/h", "cancelled", "Installation",
                new TimeWindow("s", "e"), List.of(PartyRef.customer("p-1")), "FSM-42", "tmf646",
                mapper.readTree("[{\"id\":\"o-1\"}]"), mapper.readTree("{\"city\":\"Oslo\"}"), CLOCK, CLOCK)))
                .contains("\"status\":\"cancelled\",\"description\":\"Installation\",\"validFor\"")
                .contains("\"relatedParty\":[{\"role\":\"customer\",\"@referredType\":\"Individual\","
                        + "\"id\":\"p-1\"}],\"externalId\":\"FSM-42\",\"provider\":\"tmf646\",")
                .contains("\"relatedEntity\":[{\"id\":\"o-1\"}],\"place\":{\"city\":\"Oslo\"},");
    }

    @Test
    void theSlotGridAnswersWithAtTypeSecondAndEchoesThePlaceOnlyWhenOneCame() throws Exception {
        assertThat(write(new SearchTimeSlotResult("s-1", "done", "2026-09-23T08:00Z", "success",
                "Europe/Oslo", "roster", null,
                List.of(new SlotView(new TimeWindow("a", "b"), 3)))))
                .isEqualTo("{\"id\":\"s-1\",\"@type\":\"SearchTimeSlot\",\"status\":\"done\","
                        + "\"searchDate\":\"2026-09-23T08:00Z\",\"searchResult\":\"success\","
                        + "\"timezone\":\"Europe/Oslo\",\"provider\":\"roster\","
                        + "\"availableTimeSlot\":[{\"validFor\":{\"startDateTime\":\"a\","
                        + "\"endDateTime\":\"b\"},\"remaining\":3}]}");
        assertThat(write(new SearchTimeSlotResult("s-1", "done", "c", "no availability", "Europe/Oslo",
                "tmf646", mapper.readTree("{\"city\":\"Georgetown\"}"), List.of())))
                .contains("\"provider\":\"tmf646\",\"relatedPlace\":{\"city\":\"Georgetown\"},"
                        + "\"availableTimeSlot\":[]");
    }

    @Test
    void theCalendarWritesANullLastUpdateAndLeavesOffTheProviderKeys() throws Exception {
        assertThat(write(new ScheduleConfigView("genalpha", "Europe/Oslo",
                List.of("MON", "TUE"), List.of("09:00"), 2, 7, 3, 0, "flat", "roster",
                null, null, null, null)))
                .isEqualTo("{\"tenantId\":\"genalpha\",\"timezone\":\"Europe/Oslo\","
                        + "\"workingDays\":[\"MON\",\"TUE\"],\"slotStarts\":[\"09:00\"],\"slotHours\":2,"
                        + "\"daysAhead\":7,\"defaultCapacity\":3,\"rosterSize\":0,\"capacityMode\":\"flat\","
                        + "\"provider\":\"roster\",\"lastUpdate\":null,\"@type\":\"ScheduleConfig\"}");
        assertThat(write(new ScheduleConfigView("enet", "America/Guyana", List.of("MON"), List.of("08:00"),
                2, 10, 2, 3, "provider", "tmf646", "http://mock-fsm:8080", "FSM_KEY", "fibre-install", CLOCK)))
                .contains("\"provider\":\"tmf646\",\"providerUrl\":\"http://mock-fsm:8080\","
                        + "\"providerSecretRef\":\"FSM_KEY\",\"providerCategory\":\"fibre-install\","
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\"");
    }

    @Test
    void aRosterRowLeavesOffOnlyTheZone() throws Exception {
        assertThat(write(new TechnicianView("t-1", "Asha Persaud", List.of("fibre", "tv"), null,
                List.of("MON", "TUE"), "08:00", "17:00", true, CLOCK, CLOCK)))
                .isEqualTo("{\"id\":\"t-1\",\"name\":\"Asha Persaud\",\"skills\":[\"fibre\",\"tv\"],"
                        + "\"workingDays\":[\"MON\",\"TUE\"],\"startTime\":\"08:00\",\"endTime\":\"17:00\","
                        + "\"active\":true,\"creationDate\":\"2026-09-23T08:00:00Z\","
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"Technician\"}");
        assertThat(write(new TechnicianView("t-1", "Asha", List.of(), "Georgetown",
                List.of(), "08:00", "17:00", false, null, null)))
                .contains("\"skills\":[],\"zone\":\"Georgetown\",\"workingDays\":[]");
    }

    @Test
    void theProbeAndThePrivacyReceiptsKeepTheirMapOfOrders() throws Exception {
        assertThat(write(new ProbeResult(true, "built-in roster", "roster")))
                .isEqualTo("{\"ok\":true,\"detail\":\"built-in roster\",\"provider\":\"roster\"}");
        assertThat(write(PrivacyExport.of("appointments", List.of())))
                .isEqualTo("{\"items\":[],\"category\":\"appointments\",\"count\":0}");
        assertThat(write(EraseReceipt.of("appointments", 4)))
                .isEqualTo("{\"retained\":0,\"category\":\"appointments\",\"deleted\":4}");
    }

    @Test
    void aPostedSearchKeepsTheMapsShapeTest() throws Exception {
        SearchTimeSlotRequest ok = mapper.readValue("{\"relatedPlace\":{\"city\":\"Oslo\"},"
                + "\"relatedEntity\":[{\"id\":\"o-1\"}],\"relatedParty\":{\"id\":\"p-1\"},"
                + "\"requestedTimeSlot\":[{\"validFor\":{}}],\"unknown\":1}", SearchTimeSlotRequest.class);
        assertThat(ok.place().toString()).isEqualTo("{\"city\":\"Oslo\"}");
        assertThat(ok.entities().size()).isEqualTo(1);
        assertThat(ok.party().toString()).isEqualTo("{\"id\":\"p-1\"}");
        assertThat(ok.windows().size()).isEqualTo(1);

        // the wrong JSON kind was dropped by the map's instanceof test and still is
        SearchTimeSlotRequest wrong = mapper.readValue("{\"relatedPlace\":\"Storgata 1\","
                + "\"relatedEntity\":\"an-order\",\"relatedParty\":[\"a\"],"
                + "\"requestedTimeSlot\":{\"not\":\"a list\"}}", SearchTimeSlotRequest.class);
        assertThat(wrong.place()).isNull();
        assertThat(wrong.entities()).isNull();
        assertThat(wrong.party()).isNull();
        assertThat(wrong.windows()).isNull();
    }

    @Test
    void aPostedBookingTellsPlaceFromRelatedPlaceAndAnExplicitNullFromAnAbsentKey() throws Exception {
        AppointmentRequest both = mapper.readValue("{\"place\":{\"a\":1},\"relatedPlace\":{\"b\":2}}",
                AppointmentRequest.class);
        assertThat(both.placeDocument().toString()).isEqualTo("{\"a\":1}");
        AppointmentRequest onlyRelated = mapper.readValue("{\"relatedPlace\":{\"b\":2}}",
                AppointmentRequest.class);
        assertThat(onlyRelated.placeDocument().toString()).isEqualTo("{\"b\":2}");
        // an explicit JSON null read as a missing key through the map's get, and still does
        AppointmentRequest nulled = mapper.readValue("{\"place\":null,\"relatedPlace\":{\"b\":2},"
                + "\"relatedEntity\":null,\"description\":null}", AppointmentRequest.class);
        assertThat(nulled.placeDocument().toString()).isEqualTo("{\"b\":2}");
        assertThat(nulled.entityDocument()).isNull();
        assertThat(nulled.descriptionText()).isNull();
        // a non-object place was never handed to the seam, but was still stored verbatim
        AppointmentRequest text = mapper.readValue("{\"place\":\"Storgata 1\"}", AppointmentRequest.class);
        assertThat(text.placeObject()).isNull();
        assertThat(text.placeDocument().asText()).isEqualTo("Storgata 1");
        assertThat(AppointmentRequest.EMPTY.window()).isNull();
    }

    @Test
    void onlyAJsonStringCancelsABooking() throws Exception {
        assertThat(mapper.readValue("{\"status\":\"cancelled\"}", AppointmentPatch.class)
                .cancelling("cancelled")).isTrue();
        assertThat(mapper.readValue("{\"status\":\"completed\"}", AppointmentPatch.class)
                .cancelling("cancelled")).isFalse();
        assertThat(mapper.readValue("{}", AppointmentPatch.class).cancelling("cancelled")).isFalse();
        assertThat(mapper.readValue("{\"status\":null}", AppointmentPatch.class)
                .cancelling("cancelled")).isFalse();
    }

    /** The map could not tell an absent key from an explicit null; these helpers re-join them. */
    @Test
    void theMapsLeniencySurvivesTheRecord() throws Exception {
        ScheduleConfigRequest req = mapper.readValue("{\"timezone\":null,\"provider\":null,"
                + "\"slotHours\":\"two\",\"workingDays\":[\"mon\",2],\"providerUrl\":null}",
                ScheduleConfigRequest.class);
        assertThat(Json.present(req.timezone())).isTrue();
        assertThat(Json.valueOf(req.timezone())).isEqualTo("null");      // the literal the map minted
        assertThat(Json.textOrNull(req.provider())).isNull();            // "back to the roster"
        assertThat(Json.valueOf(req.slotHours())).isEqualTo("two");
        assertThat(Json.join(req.workingDays())).isEqualTo("mon,2");
        assertThat(Json.present(req.daysAhead())).isFalse();
        assertThat(Json.textOrNull(req.providerUrl())).isNull();

        TechnicianRequest named = mapper.readValue("{\"name\":\"Asha\"}", TechnicianRequest.class);
        assertThat(named.named()).isTrue();
        assertThat(mapper.readValue("{\"name\":\"  \"}", TechnicianRequest.class).named()).isFalse();
        assertThat(mapper.readValue("{\"name\":null}", TechnicianRequest.class).named()).isFalse();
        assertThat(TechnicianRequest.EMPTY.named()).isFalse();
        assertThat(Json.valueOf(mapper.readValue("{\"active\":\"yes\"}", TechnicianRequest.class).active()))
                .isEqualTo("yes");
    }
}
