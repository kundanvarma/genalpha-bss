package com.bss.assurance;

import com.bss.assurance.dto.AlarmPatch;
import com.bss.assurance.dto.AlarmRef;
import com.bss.assurance.dto.AlarmRequest;
import com.bss.assurance.dto.AlarmView;
import com.bss.assurance.dto.CustomerRef;
import com.bss.assurance.dto.Json;
import com.bss.assurance.dto.PartyRef;
import com.bss.assurance.dto.ServiceProblemPatch;
import com.bss.assurance.dto.ServiceProblemRequest;
import com.bss.assurance.dto.ServiceProblemView;
import com.bss.assurance.dto.SlaView;
import com.bss.assurance.dto.SlaViolationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes, the key order and the credit scale of the assurance
 * loop. The two party blocks came out of {@code Map.of}s that are re-salted on
 * every JVM start — their orders were read off a snapshot of the running
 * container, and the two TM Forum CTKs (TMF642, TMF656) hold them to it.
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
    void everyTmf642MandatoryAttributeRidesEveryAlarm() throws Exception {
        assertThat(write(new AlarmView("a-1", "/tmf-api/alarmManagement/v4/alarm/a-1",
                "fibre-route-oslo", "equipmentAlarm", "critical", "raised",
                "unknown", "network", "2026-09-23T08:00:00Z")))
                .isEqualTo("{\"id\":\"a-1\",\"href\":\"/tmf-api/alarmManagement/v4/alarm/a-1\","
                        + "\"alarmedObject\":\"fibre-route-oslo\",\"alarmType\":\"equipmentAlarm\","
                        + "\"perceivedSeverity\":\"critical\",\"state\":\"raised\","
                        + "\"probableCause\":\"unknown\",\"sourceSystemId\":\"network\","
                        + "\"alarmRaisedTime\":\"2026-09-23T08:00:00Z\",\"@type\":\"Alarm\"}");
    }

    @Test
    void aProblemKeepsBothPartyBlocksInTheOrderTheWireHas() throws Exception {
        assertThat(write(PartyRef.operations("genalpha")))
                .isEqualTo("{\"name\":\"network operations\",\"id\":\"op-genalpha\","
                        + "\"role\":\"operations\"}");
        assertThat(write(PartyRef.monitoringSystem()))
                .isEqualTo("{\"name\":\"assurance loop\",\"id\":\"assurance\","
                        + "\"role\":\"monitoringSystem\"}");
        // an alarm-born problem: the originator is the house default, and underlyingAlarm rides
        assertThat(write(new ServiceProblemView("p-1", "/h", "Outage: fibre-route-oslo", "cut",
                "open", "fibre-route-oslo", "serviceProvider.declared", 1, "cut",
                mapper.valueToTree(PartyRef.monitoringSystem()), PartyRef.operations("genalpha"),
                1, "t1", "t2", "t2", List.of(new AlarmRef("a-1")))))
                .isEqualTo("{\"id\":\"p-1\",\"href\":\"/h\",\"name\":\"Outage: fibre-route-oslo\","
                        + "\"description\":\"cut\",\"status\":\"open\","
                        + "\"affectedObject\":\"fibre-route-oslo\","
                        + "\"category\":\"serviceProvider.declared\",\"priority\":1,\"reason\":\"cut\","
                        + "\"originatorParty\":{\"name\":\"assurance loop\",\"id\":\"assurance\","
                        + "\"role\":\"monitoringSystem\"},\"responsibleParty\":"
                        + "{\"name\":\"network operations\",\"id\":\"op-genalpha\","
                        + "\"role\":\"operations\"},\"affectedNumberOfServices\":1,"
                        + "\"timeRaised\":\"t1\",\"timeChanged\":\"t2\",\"statusChangeDate\":\"t2\","
                        + "\"underlyingAlarm\":[{\"id\":\"a-1\"}],\"@type\":\"ServiceProblem\"}");
        // a DECLARED problem: the originator is the caller's own block, in the caller's own
        // key order, and nothing underlies it
        assertThat(write(new ServiceProblemView("p-2", "/h", "Snap", "d", "open", "declared",
                "x.declared", 3, "r", mapper.readTree("{\"id\":\"noc\",\"role\":\"noc\"}"),
                PartyRef.operations("genalpha"), 0, "t", "t", "t", null)))
                .contains("\"originatorParty\":{\"id\":\"noc\",\"role\":\"noc\"}")
                .doesNotContain("underlyingAlarm");
    }

    @Test
    void theLedgerKeepsTheStoredCreditScale() throws Exception {
        assertThat(write(new SlaViolationView("v-1", "ag-1", "p-1", "sla-circuit-1", 0L, 1L,
                new BigDecimal("0.00"), false, "breach recorded",
                List.of(CustomerRef.customer("party-1")), CLOCK)))
                .isEqualTo("{\"id\":\"v-1\",\"agreementId\":\"ag-1\",\"problemId\":\"p-1\","
                        + "\"affectedObject\":\"sla-circuit-1\",\"thresholdMinutes\":0,"
                        + "\"durationMinutes\":1,\"creditAmount\":0.00,\"credited\":false,"
                        + "\"note\":\"breach recorded\",\"relatedParty\":"
                        + "[{\"role\":\"customer\",\"id\":\"party-1\"}],"
                        + "\"createdAt\":\"2026-09-23T08:00:00Z\",\"@type\":\"SlaViolation\"}");
        assertThat(write(new SlaViolationView("v-2", "ag", "p", "o", 30L, 90L,
                new BigDecimal("50.00"), true, "late", null, CLOCK)))
                .contains("\"creditAmount\":50.00,\"credited\":true,\"note\":\"late\","
                        + "\"createdAt\"");
    }

    @Test
    void anSlaRowIsTheAgreementsOwnDocumentBesideOneHouseName() throws Exception {
        assertThat(write(new SlaView(mapper.readTree("\"ag-1\""), "SLA — Enterprise circuit",
                mapper.readTree("\"active\""),
                mapper.readTree("[{\"id\":\"p-1\",\"role\":\"customer\"}]"),
                mapper.readTree("{\"affectedObject\":\"c-1\",\"thresholdMinutes\":0}"))))
                .isEqualTo("{\"id\":\"ag-1\",\"name\":\"SLA — Enterprise circuit\","
                        + "\"state\":\"active\",\"relatedParty\":"
                        + "[{\"id\":\"p-1\",\"role\":\"customer\"}],"
                        + "\"template\":{\"affectedObject\":\"c-1\",\"thresholdMinutes\":0},"
                        + "\"@type\":\"SLA\"}");
    }

    /**
     * The map printed a nested block with Java's own toString, and a number as its
     * digits. Both reach the alarm table today, so both are contract.
     */
    @Test
    void theAlarmedObjectKeepsEveryShapeTheMapPrinted() throws Exception {
        assertThat(mapper.readValue("{\"alarmedObject\":{\"id\":\"obj-1\"}}", AlarmRequest.class)
                .alarmedObjectId()).isEqualTo("obj-1");
        assertThat(mapper.readValue("{\"alarmedObject\":\"obj-1\"}", AlarmRequest.class)
                .alarmedObjectId()).isEqualTo("obj-1");
        assertThat(mapper.readValue("{\"alarmedObject\":{\"noId\":\"obj-1\"}}", AlarmRequest.class)
                .alarmedObjectId()).isEqualTo("{noId=obj-1}");
        assertThat(mapper.readValue("{\"alarmedObject\":[\"a\",\"list\"]}", AlarmRequest.class)
                .alarmedObjectId()).isEqualTo("[a, list]");
        assertThat(mapper.readValue("{\"alarmedObject\":{\"id\":7}}", AlarmRequest.class)
                .alarmedObjectId()).isEqualTo("7");

        AlarmRequest numbers = mapper.readValue(
                "{\"alarmedObject\":\"o\",\"perceivedSeverity\":7,\"alarmType\":5,"
                + "\"probableCause\":9,\"sourceSystemId\":3}", AlarmRequest.class);
        assertThat(numbers.severity()).isEqualTo("7");
        assertThat(numbers.alarmTypeOr("equipmentAlarm")).isEqualTo("5");
        assertThat(numbers.probableCauseOrNull()).isEqualTo("9");
        assertThat(numbers.sourceSystemIdOr("network")).isEqualTo("3");

        assertThat(AlarmRequest.EMPTY.complete()).isFalse();
        assertThat(mapper.readValue("{\"alarmedObject\":null,\"perceivedSeverity\":\"minor\"}",
                AlarmRequest.class).complete()).isFalse();
        assertThat(mapper.readValue("{\"alarmedObject\":\"o\",\"perceivedSeverity\":\"minor\"}",
                AlarmRequest.class).complete()).isTrue();
        assertThat(mapper.readValue("{}", AlarmRequest.class).alarmTypeOr("equipmentAlarm"))
                .isEqualTo("equipmentAlarm");
    }

    @Test
    void anExplicitNullLeavesAnAlarmColumnAlone() throws Exception {
        AlarmPatch nulls = mapper.readValue(
                "{\"probableCause\":null,\"perceivedSeverity\":null,\"alarmType\":null,"
                + "\"state\":null}", AlarmPatch.class);
        assertThat(Json.set(nulls.probableCause())).isFalse();
        assertThat(Json.set(nulls.perceivedSeverity())).isFalse();
        assertThat(Json.set(nulls.alarmType())).isFalse();
        assertThat(Json.set(nulls.state())).isFalse();
        assertThat(Json.set(mapper.readValue("{\"state\":\"cleared\"}", AlarmPatch.class).state()))
                .isTrue();
        assertThat(Json.set(AlarmPatch.EMPTY.state())).isFalse();
    }

    @Test
    void aProblemIsDeclaredByAnObjectThatNamesARole() throws Exception {
        assertThat(mapper.readValue("{\"originatorParty\":{\"role\":\"noc\"}}",
                ServiceProblemRequest.class).originator()).isNotNull();
        assertThat(mapper.readValue("{\"originatorParty\":{\"id\":\"noc\"}}",
                ServiceProblemRequest.class).originator()).isNull();
        assertThat(mapper.readValue("{\"originatorParty\":\"noc\"}",
                ServiceProblemRequest.class).originator()).isNull();
        assertThat(ServiceProblemRequest.EMPTY.originator()).isNull();

        ServiceProblemRequest r = mapper.readValue("{\"description\":7,\"priority\":4}",
                ServiceProblemRequest.class);
        assertThat(r.descriptionValue()).isEqualTo("7");
        assertThat(r.nameOrDescription()).isEqualTo("7");
        assertThat(r.priorityOr(2)).isEqualTo(4);
        // a string priority was parsed, an absent one defaulted
        assertThat(mapper.readValue("{\"priority\":\"3\"}", ServiceProblemRequest.class)
                .priorityOr(2)).isEqualTo(3);
        assertThat(mapper.readValue("{}", ServiceProblemRequest.class).priorityOr(2)).isEqualTo(2);
        assertThat(mapper.readValue("{}", ServiceProblemRequest.class)
                .affectedObjectOr("declared")).isEqualTo("declared");
        assertThat(mapper.readValue("{\"reason\":null}", ServiceProblemRequest.class)
                .reasonOr("unknown")).isEqualTo("unknown");

        assertThat(mapper.readValue("{\"status\":\"resolved\"}", ServiceProblemPatch.class)
                .resolving()).isTrue();
        assertThat(mapper.readValue("{\"status\":\"open\"}", ServiceProblemPatch.class)
                .resolving()).isFalse();
        assertThat(mapper.readValue("{}", ServiceProblemPatch.class).resolving()).isFalse();
    }
}
