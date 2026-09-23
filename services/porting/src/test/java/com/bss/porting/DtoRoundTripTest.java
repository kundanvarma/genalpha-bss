package com.bss.porting;

import com.bss.porting.dto.EntityRef;
import com.bss.porting.dto.PartyRef;
import com.bss.porting.dto.PortedNumber;
import com.bss.porting.dto.PortingOrderRequest;
import com.bss.porting.dto.PortingOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of porting's wire records. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static PortingOrderView view(String status, String rejectReason,
            String scheduled, String completed, EntityRef order, List<PartyRef> parties) {
        return new PortingOrderView("o-1",
                "/tmf-api/numberPortingManagement/v1/numberPortingOrder/o-1", "portIn",
                "+4791234571", "NO", "Snapshot Telecom", status, "nrdb", "Nkom (via NRDB)",
                rejectReason, null, scheduled, completed, order, parties, "PortingOrder");
    }

    @Test
    void aScheduledPortWritesTheKeysTheMapWroteInTheOrderItWroteThem() throws Exception {
        assertThat(write(view("scheduled", null, "2026-09-24T08:00Z", null,
                new EntityRef("order-1"), List.of(PartyRef.customer("party-1")))))
                .isEqualTo("{\"id\":\"o-1\",\"href\":\"/tmf-api/numberPortingManagement/v1/"
                        + "numberPortingOrder/o-1\",\"direction\":\"portIn\","
                        + "\"phoneNumber\":\"+4791234571\",\"country\":\"NO\","
                        + "\"otherOperator\":\"Snapshot Telecom\",\"status\":\"scheduled\","
                        + "\"clearinghouse\":\"nrdb\",\"regulator\":\"Nkom (via NRDB)\","
                        + "\"scheduledCutover\":\"2026-09-24T08:00Z\","
                        + "\"productOrder\":{\"id\":\"order-1\"},"
                        + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],"
                        + "\"@type\":\"PortingOrder\"}");
    }

    @Test
    void aRefusalCarriesTheReasonAndNoCutoverAtAll() throws Exception {
        assertThat(write(view("rejected", "donor operator rejected the port via NRDB", null, null,
                null, null)))
                .isEqualTo("{\"id\":\"o-1\",\"href\":\"/tmf-api/numberPortingManagement/v1/"
                        + "numberPortingOrder/o-1\",\"direction\":\"portIn\","
                        + "\"phoneNumber\":\"+4791234571\",\"country\":\"NO\","
                        + "\"otherOperator\":\"Snapshot Telecom\",\"status\":\"rejected\","
                        + "\"clearinghouse\":\"nrdb\",\"regulator\":\"Nkom (via NRDB)\","
                        + "\"rejectReason\":\"donor operator rejected the port via NRDB\","
                        + "\"@type\":\"PortingOrder\"}");
    }

    @Test
    void aCompletedPortShowsTheCutoverAndTheMomentItLanded() throws Exception {
        assertThat(write(view("completed", null, "2026-09-24T08:00Z", "2026-09-24T08:01Z",
                null, List.of(PartyRef.customer("party-1")))))
                .contains("\"scheduledCutover\":\"2026-09-24T08:00Z\","
                        + "\"completedAt\":\"2026-09-24T08:01Z\",");
    }

    @Test
    void noPortedNumberIsAnEmptyAnswerNotAMissingResource() throws Exception {
        assertThat(write(PortedNumber.NONE)).isEqualTo("{}");
        assertThat(write(new PortedNumber("+4791234571", "o-1")))
                .isEqualTo("{\"phoneNumber\":\"+4791234571\",\"portingOrderId\":\"o-1\"}");
    }

    @Test
    void thePartyBlockIsTheOneKeyOrderThisComponentPins() throws Exception {
        // the map path built this from a two-entry Map.of, re-salted per JVM
        assertThat(write(PartyRef.customer("party-1")))
                .isEqualTo("{\"id\":\"party-1\",\"role\":\"customer\"}");
    }

    /* ---------- the request ---------- */

    @Test
    void onlyTheFirstPartysIdIsRead() throws Exception {
        assertThat(mapper.readValue(
                "{\"relatedParty\":[{\"id\":\"p-1\",\"role\":\"customer\"},{\"id\":\"p-2\"}]}",
                PortingOrderRequest.class).firstPartyId()).isEqualTo("p-1");
        assertThat(mapper.readValue("{\"relatedParty\":[]}", PortingOrderRequest.class)
                .firstPartyId()).isNull();
        assertThat(mapper.readValue("{\"relatedParty\":[{\"role\":\"customer\"}]}",
                PortingOrderRequest.class).firstPartyId()).isNull();
        assertThat(mapper.readValue("{\"relatedParty\":\"p-1\"}", PortingOrderRequest.class)
                .firstPartyId()).isNull();
        assertThat(mapper.readValue("{}", PortingOrderRequest.class).firstPartyId()).isNull();
    }

    @Test
    void aNumberPostedAsANumberStillArrivesAsTheTextTheMapMadeOfIt() throws Exception {
        assertThat(mapper.readValue("{\"phoneNumber\":4791234571,\"country\":\"no\"}",
                PortingOrderRequest.class).phoneNumber()).isEqualTo("4791234571");
        assertThat(mapper.readValue("{\"phoneNumber\":null}", PortingOrderRequest.class)
                .phoneNumber()).isNull();
    }

    @Test
    void theBodyCannotCarryAFieldTheServiceNeverDeclared() throws Exception {
        PortingOrderRequest req = mapper.readValue(
                "{\"phoneNumber\":\"+4791234571\",\"country\":\"NO\",\"status\":\"completed\","
                        + "\"tenantId\":\"other\",\"clearinghouse\":\"mine\"}",
                PortingOrderRequest.class);
        assertThat(req.country()).isEqualTo("NO");
        assertThat(req.direction()).isNull();
        assertThat(req.requestedCutover()).isNull();
    }
}
