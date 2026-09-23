package com.bss.basemigration;

import com.bss.basemigration.dto.AttachSimulationRequest;
import com.bss.basemigration.dto.MigrationCustomerDetail;
import com.bss.basemigration.dto.MigrationCustomerView;
import com.bss.basemigration.dto.MigrationEvents;
import com.bss.basemigration.dto.MigrationPlanRequest;
import com.bss.basemigration.dto.MigrationPlanView;
import com.bss.basemigration.dto.MigrationProgress;
import com.bss.basemigration.dto.TriggerScanResult;
import com.bss.basemigration.entity.MigrationCustomer;
import com.bss.basemigration.entity.MigrationPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of the migration desk's wire. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    // ---- the plan ----

    @Test
    void aDraftPlanWritesTheKeysTheMapWroteAndLeavesOffTheTwoItLeftOff() throws Exception {
        assertThat(write(plan(null, null)))
                .isEqualTo("{\"id\":\"p-1\",\"href\":\"/tmf-api/baseMigration/v1/migrationPlan/p-1\","
                        + "\"name\":\"Sunset Legacy 79\",\"state\":\"draft\","
                        + "\"matrix\":[{\"sourceOfferingId\":\"off-legacy\",\"targetOfferingId\":\"off-new\","
                        + "\"deltaClass\":\"neutral\"}],"
                        + "\"eligibility\":{\"inBinding\":\"defer-to-expiry\"},"
                        + "\"trigger\":{},\"jurisdictionPack\":{\"noticeDays\":30},"
                        + "\"noticeDays\":30,\"grandfatheredPartyIds\":[],\"simulationRef\":null,"
                        + "\"maxOrdersPerRun\":10,\"breakerThreshold\":3,\"consecutiveFailures\":0,"
                        + "\"createdAt\":\"2026-09-23T08:00Z\",\"lastUpdate\":\"2026-09-23T08:00Z\","
                        + "\"@type\":\"MigrationPlan\"}");
    }

    @Test
    void aSimulatedPlanNamesItsReceiptAndAnArmedOneItsCohort() throws Exception {
        assertThat(write(plan("sim-1", "2026-09-23T08:01Z")))
                .contains("\"simulationRef\":\"sim-1\",\"simulationAttachedAt\":\"2026-09-23T08:01Z\","
                        + "\"maxOrdersPerRun\":10");
        assertThat(write(plan("sim-1", "2026-09-23T08:01Z").withDiscovered(2)))
                .endsWith("\"@type\":\"MigrationPlan\",\"customersDiscovered\":2}");
    }

    /** The operator's four documents come back exactly as they were stored. */
    @Test
    void theOperatorsOwnBlocksAreNotReshaped() throws Exception {
        MigrationPlanView view = new MigrationPlanView("p-1", "/h", "n", "draft",
                tree("[{\"targetOfferingName\":\"New\",\"sourceOfferingId\":\"a\",\"targetOfferingId\":\"b\","
                        + "\"deltaClass\":\"detrimental\",\"characteristicMap\":{\"simType\":\"keep\"}}]"),
                tree("{\"segmentExcludes\":[\"NO-03\"],\"inBinding\":\"exclude\"}"),
                tree("{\"type\":\"age-threshold\",\"ageYears\":2,\"strategy\":\"auto-migrate\"}"),
                tree("{\"exitRightByDeltaClass\":{\"neutral\":true},\"noticeDays\":30}"),
                30, List.of("party-9"), null, null, 10, 3, 0, null, null, "MigrationPlan", null);
        assertThat(write(view))
                .contains("\"matrix\":[{\"targetOfferingName\":\"New\",\"sourceOfferingId\":\"a\","
                        + "\"targetOfferingId\":\"b\",\"deltaClass\":\"detrimental\","
                        + "\"characteristicMap\":{\"simType\":\"keep\"}}]")
                .contains("\"eligibility\":{\"segmentExcludes\":[\"NO-03\"],\"inBinding\":\"exclude\"}")
                .contains("\"jurisdictionPack\":{\"exitRightByDeltaClass\":{\"neutral\":true},\"noticeDays\":30}")
                .contains("\"grandfatheredPartyIds\":[\"party-9\"]");
    }

    // ---- the subscriber's journey ----

    @Test
    void aScheduledCustomerCarriesNoClockItHasNotReachedYet() throws Exception {
        assertThat(write(MigrationCustomerView.of(customer("scheduled", false))))
                .isEqualTo("{\"id\":\"c-1\",\"planId\":\"p-1\",\"partyId\":\"party-1\",\"productId\":\"prod-1\","
                        + "\"sourceOffering\":{\"name\":\"Legacy 79\",\"id\":\"off-legacy\"},"
                        + "\"targetOffering\":{\"name\":\"Flex 99\",\"id\":\"off-new\"},"
                        + "\"deltaClass\":\"detrimental\",\"state\":\"scheduled\",\"exitRight\":true,"
                        + "\"penaltyFreeExit\":false,\"scheduledFor\":\"2026-09-23T08:00Z\"}");
    }

    @Test
    void aMigratedCustomerNamesItsOrderAndAFailedOneItsReason() throws Exception {
        assertThat(write(MigrationCustomerView.of(customer("migrated", true))))
                .endsWith("\"penaltyFreeExit\":false,\"scheduledFor\":\"2026-09-23T08:00Z\","
                        + "\"noticeSentAt\":\"2026-09-23T08:05Z\",\"orderRef\":\"ord-1\"}");
        MigrationCustomer failed = customer("failed", true);
        failed.setOrderRef(null);
        failed.setFailureReason("ordering said no");
        assertThat(write(MigrationCustomerView.of(failed)))
                .endsWith("\"noticeSentAt\":\"2026-09-23T08:05Z\",\"failureReason\":\"ordering said no\"}");
    }

    /** A nameless offering shows an empty name; a missing id keeps the literal the map minted. */
    @Test
    void anOfferingRefKeepsTheMapsOwnLeniency() throws Exception {
        assertThat(write(MigrationCustomerView.OfferingRef.of("off-1", null)))
                .isEqualTo("{\"name\":\"\",\"id\":\"off-1\"}");
        assertThat(write(MigrationCustomerView.OfferingRef.of(null, "New")))
                .isEqualTo("{\"name\":\"New\",\"id\":\"null\"}");
    }

    @Test
    void theDetailUnwrapsTheJourneyBeforeTheSnapshot() throws Exception {
        assertThat(write(new MigrationCustomerDetail(MigrationCustomerView.of(customer("migrated", true)),
                null, tree("{\"id\":\"prod-1\",\"status\":\"active\"}"), "2026-09-23T07:59Z",
                "MigrationCustomer")))
                .startsWith("{\"id\":\"c-1\",\"planId\":\"p-1\"")
                .endsWith("\"orderRef\":\"ord-1\",\"snapshot\":{\"id\":\"prod-1\",\"status\":\"active\"},"
                        + "\"createdAt\":\"2026-09-23T07:59Z\",\"@type\":\"MigrationCustomer\"}");
        MigrationCustomer rolled = customer("rolled-back", true);
        assertThat(write(new MigrationCustomerDetail(MigrationCustomerView.of(rolled), "ord-2",
                mapper.createObjectNode(), "2026-09-23T07:59Z", "MigrationCustomer")))
                .contains("\"orderRef\":\"ord-1\",\"rollbackOrderRef\":\"ord-2\",\"snapshot\":{}");
    }

    // ---- progress and the trigger scans ----

    @Test
    void progressCountsEveryStateInTheEnginesOwnOrder() throws Exception {
        Map<String, Long> byState = new LinkedHashMap<>();
        byState.put("scheduled", 0L);
        byState.put("migrated", 2L);
        assertThat(write(new MigrationProgress("p-1", "Sunset", "done", 0, 2L, byState, List.of())))
                .isEqualTo("{\"planId\":\"p-1\",\"name\":\"Sunset\",\"state\":\"done\","
                        + "\"consecutiveFailures\":0,\"totalCustomers\":2,"
                        + "\"byState\":{\"scheduled\":0,\"migrated\":2},\"grandfatheredPartyIds\":[]}");
    }

    @Test
    void eachTriggerKindAnswersItsOwnDocument() throws Exception {
        assertThat(write(TriggerScanResult.Bulk.of("bulk")))
                .isEqualTo("{\"note\":\"bulk plans discover on arm; nothing to scan\","
                        + "\"triggerType\":\"bulk\",\"scheduled\":0}");
        assertThat(write(new TriggerScanResult.Age("age-threshold", "auto-migrate", 3, 0)))
                .isEqualTo("{\"triggerType\":\"age-threshold\",\"strategy\":\"auto-migrate\","
                        + "\"scheduled\":3,\"grandfathered\":0}");
        assertThat(write(new TriggerScanResult.Promo("promo-expiry", null, false, 0)))
                .isEqualTo("{\"triggerType\":\"promo-expiry\",\"endDate\":null,\"due\":false,\"scheduled\":0}");
    }

    // ---- the events ----

    @Test
    void theWaveEventsCarryThePlanAndTheNoticeCarriesTheSentence() throws Exception {
        MigrationPlan plan = new MigrationPlan();
        plan.setId("p-1");
        plan.setName("Sunset");
        plan.setState("armed");
        plan.setTriggerType("bulk");
        plan.setNoticeDays(30);
        plan.setSimulationRef("sim-1");
        assertThat(write(MigrationEvents.PlanEvent.of(plan)))
                .isEqualTo("{\"id\":\"p-1\",\"name\":\"Sunset\",\"state\":\"armed\",\"triggerType\":\"bulk\","
                        + "\"noticeDays\":30,\"simulationRef\":\"sim-1\",\"consecutiveFailures\":0}");
        assertThat(write(MigrationEvents.PlanEvent.of(plan).withDiscovered(2)))
                .endsWith("\"consecutiveFailures\":0,\"customersDiscovered\":2}");
        MigrationCustomer noticed = customer("noticed", true);
        noticed.setOrderRef(null); // no order has gone out yet — that is what the gate is for
        assertThat(write(new MigrationEvents.CustomerNoticed(
                MigrationCustomerView.of(noticed), "2026-10-23T08:05Z",
                "Your plan 'Legacy 79' is changing to 'Flex 99' (detrimental change).")))
                .startsWith("{\"id\":\"c-1\"")
                .endsWith("\"noticeSentAt\":\"2026-09-23T08:05Z\","
                        + "\"earliestOrderDate\":\"2026-10-23T08:05Z\","
                        + "\"changeSummary\":\"Your plan 'Legacy 79' is changing to 'Flex 99' "
                        + "(detrimental change).\"}");
    }

    // ---- the request bodies: the map's leniency IS the contract ----

    @Test
    void aMentionedBlockIsNotTheSameAsAGivenOne() throws Exception {
        MigrationPlanRequest patch = mapper.readValue("{\"trigger\":null}", MigrationPlanRequest.class);
        assertThat(MigrationPlanRequest.mentioned(patch.trigger())).isTrue();
        assertThat(MigrationPlanRequest.given(patch.trigger())).isFalse();
        assertThat(patch.touchesSubstance()).isTrue();
        MigrationPlanRequest rename = mapper.readValue("{\"name\":\"n\",\"unknownToUs\":1}",
                MigrationPlanRequest.class);
        assertThat(rename.touchesSubstance()).isFalse();
        assertThat(MigrationPlanRequest.text(rename.name())).isEqualTo("n");
    }

    @Test
    void aStringIsNotANumberAndAnAbsentValueIsTheLiteralNull() throws Exception {
        MigrationPlanRequest r = mapper.readValue(
                "{\"maxOrdersPerRun\":\"5\",\"breakerThreshold\":2}", MigrationPlanRequest.class);
        assertThat(r.maxOrdersPerRun().isNumber()).isFalse();
        assertThat(r.breakerThreshold().isNumber()).isTrue();
        assertThat(MigrationPlanRequest.text(null)).isEqualTo("null");
        assertThat(MigrationPlanRequest.text(mapper.nullNode())).isEqualTo("null");
    }

    @Test
    void aSimulationRefIsWhateverStringValueOfSaid() throws Exception {
        assertThat(mapper.readValue("{\"simulationRef\":\"sim-1\"}", AttachSimulationRequest.class).ref())
                .isEqualTo("sim-1");
        assertThat(mapper.readValue("{\"simulationRef\":7}", AttachSimulationRequest.class).ref())
                .isEqualTo("7");
        assertThat(mapper.readValue("{\"simulationRef\":null}", AttachSimulationRequest.class).ref()).isNull();
        assertThat(mapper.readValue("{}", AttachSimulationRequest.class).ref()).isNull();
        assertThat(AttachSimulationRequest.EMPTY.ref()).isNull();
    }

    // ---- fixtures ----

    private MigrationPlanView plan(String simulationRef, String attachedAt) throws Exception {
        return new MigrationPlanView("p-1", "/tmf-api/baseMigration/v1/migrationPlan/p-1",
                "Sunset Legacy 79", "draft",
                tree("[{\"sourceOfferingId\":\"off-legacy\",\"targetOfferingId\":\"off-new\","
                        + "\"deltaClass\":\"neutral\"}]"),
                tree("{\"inBinding\":\"defer-to-expiry\"}"),
                mapper.createObjectNode(),
                tree("{\"noticeDays\":30}"),
                30, List.of(), simulationRef, attachedAt, 10, 3, 0,
                "2026-09-23T08:00Z", "2026-09-23T08:00Z", "MigrationPlan", null);
    }

    private static MigrationCustomer customer(String state, boolean noticed) {
        MigrationCustomer c = new MigrationCustomer();
        c.setId("c-1");
        c.setPlanId("p-1");
        c.setPartyId("party-1");
        c.setProductId("prod-1");
        c.setSourceOfferingId("off-legacy");
        c.setSourceOfferingName("Legacy 79");
        c.setTargetOfferingId("off-new");
        c.setTargetOfferingName("Flex 99");
        c.setDeltaClass("detrimental");
        c.setState(state);
        c.setExitRight(true);
        c.setScheduledFor(OffsetDateTime.parse("2026-09-23T08:00Z"));
        if (noticed) {
            c.setNoticeSentAt(OffsetDateTime.parse("2026-09-23T08:05Z"));
            c.setOrderRef("ord-1");
        }
        return c;
    }
}
