package com.bss.qualification;

import com.bss.qualification.dto.AccessOption;
import com.bss.qualification.dto.AccessOptionsResult;
import com.bss.qualification.dto.AlternateServiceProposal;
import com.bss.qualification.dto.Characteristic;
import com.bss.qualification.dto.CheckItemView;
import com.bss.qualification.dto.CheckServiceQualificationView;
import com.bss.qualification.dto.CoverageMapRequest;
import com.bss.qualification.dto.CoverageMapView;
import com.bss.qualification.dto.PoqCheckRequest;
import com.bss.qualification.dto.PoqCheckResult;
import com.bss.qualification.dto.QueryItemView;
import com.bss.qualification.dto.QueryServiceQualificationResult;
import com.bss.qualification.dto.Reason;
import com.bss.qualification.dto.SearchCriteria;
import com.bss.qualification.dto.ServiceQualificationRequest;
import com.bss.qualification.dto.ServiceView;
import com.bss.qualification.dto.ServiceableAreaRequest;
import com.bss.qualification.dto.ServiceableAreaView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson, no Spring context: the bytes and the key order of every
 * qualification wire record, pinned against what the map path used to write.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static ServiceView fiber() {
        return ServiceView.of("fiber", List.of(
                new Characteristic("technology", "fiber"),
                new Characteristic("maxDownstreamMbps", 1000),
                new Characteristic("maxUpstreamMbps", 1000)));
    }

    /* ---------- TMF645 check ---------- */

    @Test
    void aQualifiedItemWritesTheVerdictThenTheServiceThenTheIdAppendedLast() throws Exception {
        assertThat(write(CheckItemView.qualified(fiber()).withId("1"))).isEqualTo(
                "{\"state\":\"done\",\"qualificationItemResult\":\"qualified\","
                        + "\"service\":{\"serviceSpecification\":{\"name\":\"broadband-fiber\","
                        + "\"@referredType\":\"ServiceSpecification\"},"
                        + "\"serviceCharacteristic\":["
                        + "{\"name\":\"technology\",\"value\":\"fiber\"},"
                        + "{\"name\":\"maxDownstreamMbps\",\"value\":1000},"
                        + "{\"name\":\"maxUpstreamMbps\",\"value\":1000}],"
                        + "\"@type\":\"Service\"},\"id\":\"1\"}");
    }

    @Test
    void aRefusalLeavesTheServiceOffAndCarriesTheAlternativeLast() throws Exception {
        CheckItemView item = CheckItemView
                .refused(new Reason("noCoverage", "fiber is not available at postcode 99999"))
                .withAlternative(AlternateServiceProposal.of(fiber()))
                .withId("1");
        assertThat(write(item)).startsWith(
                "{\"state\":\"done\",\"qualificationItemResult\":\"unqualified\","
                        + "\"eligibilityUnavailabilityReason\":[{\"code\":\"noCoverage\","
                        + "\"label\":\"fiber is not available at postcode 99999\"}],"
                        + "\"alternateServiceProposal\":[{\"id\":\"alt-1\",\"alternateService\":{");
        assertThat(write(item)).endsWith("\"@type\":\"AlternateServiceProposal\"}],\"id\":\"1\"}");
        assertThat(write(item)).doesNotContain("\"service\"" + ":{\"serviceSpecification");
    }

    @Test
    void aStoredCheckItemParsesBackAndReWritesInTheSameOrder() throws Exception {
        String stored = "[{\"state\":\"done\",\"qualificationItemResult\":\"qualified\","
                + "\"service\":{\"serviceSpecification\":{\"name\":\"broadband-fiber\","
                + "\"@referredType\":\"ServiceSpecification\"},\"serviceCharacteristic\":["
                + "{\"name\":\"technology\",\"value\":\"fiber\"},"
                + "{\"name\":\"maxDownstreamMbps\",\"value\":1000}],\"@type\":\"Service\"},"
                + "\"id\":\"1\"}]";
        List<CheckItemView> items = mapper.readValue(stored,
                mapper.getTypeFactory().constructCollectionType(List.class, CheckItemView.class));
        assertThat(write(items)).isEqualTo(stored);
        assertThat(items.get(0).isQualified()).isTrue();
    }

    @Test
    void theIdEchoKeepsTheCallersOwnType() throws Exception {
        assertThat(write(CheckItemView.qualified(fiber()).withId(7))).endsWith("\"id\":7}");
    }

    @Test
    void aCheckViewWritesThePlaceVerbatimBetweenTheVerdictAndTheItems() throws Exception {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("postCode", "1110");
        place.put("city", "Oslo");
        CheckServiceQualificationView view = CheckServiceQualificationView.of("c-1",
                "/tmf-api/serviceQualificationManagement/v4/checkServiceQualification/c-1",
                "done", "qualified", place,
                List.of(CheckItemView.qualified(fiber()).withId("1")),
                OffsetDateTime.parse("2026-09-23T08:00:00Z"));
        assertThat(write(view)).startsWith("{\"id\":\"c-1\",\"href\":\"/tmf-api/"
                + "serviceQualificationManagement/v4/checkServiceQualification/c-1\","
                + "\"state\":\"done\",\"qualificationResult\":\"qualified\","
                + "\"place\":{\"postCode\":\"1110\",\"city\":\"Oslo\"},"
                + "\"serviceQualificationItem\":[{");
        assertThat(write(view)).endsWith("\"checkServiceQualificationDate\":\"2026-09-23T08:00:00Z\","
                + "\"@type\":\"CheckServiceQualification\"}");
    }

    /* ---------- TMF645 query and access options ---------- */

    @Test
    void aQueryAnswersTheCriteriaItWasAskedWith() throws Exception {
        assertThat(write(QueryServiceQualificationResult.of("q-1",
                new SearchCriteria(Map.of("postCode", "1110")),
                List.of(QueryItemView.of(1, fiber()))))).startsWith(
                "{\"id\":\"q-1\",\"state\":\"done\",\"instantSync\":true,"
                        + "\"searchCriteria\":{\"place\":{\"postCode\":\"1110\"}},"
                        + "\"serviceQualificationItem\":[{\"id\":\"1\",\"state\":\"done\","
                        + "\"qualificationItemResult\":\"qualified\",\"service\":{");
        assertThat(write(QueryServiceQualificationResult.of("q-1",
                new SearchCriteria(Map.of()), List.of())))
                .isEqualTo("{\"id\":\"q-1\",\"state\":\"done\",\"instantSync\":true,"
                        + "\"searchCriteria\":{\"place\":{}},\"serviceQualificationItem\":[],"
                        + "\"@type\":\"QueryServiceQualification\"}");
    }

    @Test
    void aWholesaleOptionLeavesTheUpstreamOffWhenTheFootprintDoesNotNameOne() throws Exception {
        assertThat(write(AccessOptionsResult.of(Map.of("postCode", "5001"), "fiber",
                List.of(AccessOption.of("NORDACCESS", "L3-activated", "fiber", 1000, 1000),
                        AccessOption.of("FJORDFIBER", "L2-VULA", "fiber", 500, null)))))
                .isEqualTo("{\"place\":{\"postCode\":\"5001\"},\"technology\":\"fiber\","
                        + "\"accessOption\":["
                        + "{\"accessOwner\":\"NORDACCESS\",\"accessLayer\":\"L3-activated\","
                        + "\"technology\":\"fiber\",\"maxDownMbps\":1000,\"maxUpMbps\":1000,"
                        + "\"@type\":\"WholesaleAccessOption\"},"
                        + "{\"accessOwner\":\"FJORDFIBER\",\"accessLayer\":\"L2-VULA\","
                        + "\"technology\":\"fiber\",\"maxDownMbps\":500,"
                        + "\"@type\":\"WholesaleAccessOption\"}],"
                        + "\"@type\":\"QueryAccessOptions\"}");
    }

    /* ---------- TMF679 ---------- */

    @Test
    void theTmf679EnvelopeKeepsTheHashMapOrderItAlwaysHad() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "1");
        item.put("qualificationItemResult", "qualified");
        assertThat(write(PoqCheckResult.of("p-1", "qualified", List.of(item)))).isEqualTo(
                "{\"productOfferingQualificationItem\":[{\"id\":\"1\","
                        + "\"qualificationItemResult\":\"qualified\"}],"
                        + "\"qualificationResult\":\"qualified\","
                        + "\"@type\":\"CheckProductOfferingQualification\","
                        + "\"id\":\"p-1\",\"state\":\"done\"}");
    }

    @Test
    void aCheckRequestWithoutItemsIsAnEmptyListNotNull() throws Exception {
        assertThat(mapper.readValue("{}", PoqCheckRequest.class)
                .productOfferingQualificationItem()).isEmpty();
        assertThat(mapper.readValue("{\"unknown\":1,\"productOfferingQualificationItem\":"
                + "[{\"id\":\"1\"}]}", PoqCheckRequest.class)
                .productOfferingQualificationItem()).hasSize(1);
        assertThat(PoqCheckRequest.of("not a list").productOfferingQualificationItem()).isEmpty();
    }

    @Test
    void aServiceableAreaWritesTheStoredOfferingBlockVerbatim() throws Exception {
        Map<String, Object> offering = new LinkedHashMap<>();
        offering.put("id", "po-1");
        offering.put("name", "Fiber 500");
        offering.put("extra", "kept");
        assertThat(write(ServiceableAreaView.of("a-1",
                "/tmf-api/productOfferingQualification/v4/serviceableArea/a-1", null,
                offering, "111", OffsetDateTime.parse("2026-09-23T08:00:00Z"))))
                .isEqualTo("{\"id\":\"a-1\",\"href\":\"/tmf-api/productOfferingQualification/v4/"
                        + "serviceableArea/a-1\",\"productOffering\":{\"id\":\"po-1\","
                        + "\"name\":\"Fiber 500\",\"extra\":\"kept\"},"
                        + "\"postcodePrefix\":\"111\",\"lastUpdate\":\"2026-09-23T08:00:00Z\","
                        + "\"@type\":\"ServiceableArea\"}");
        assertThat(write(ServiceableAreaView.of("a-1", "/h", "Region 4", offering, "4",
                OffsetDateTime.parse("2026-09-23T08:00:00Z"))))
                .contains("\"href\":\"/h\",\"name\":\"Region 4\",\"productOffering\"");
    }

    /* ---------- coverage map ---------- */

    @Test
    void aCoverageRowLeavesOffWhatTheMapLeftOff() throws Exception {
        com.bss.qualification.entity.CoverageMap row = new com.bss.qualification.entity.CoverageMap();
        row.setId("c-1");
        row.setHref("/tmf-api/serviceQualificationManagement/v4/coverageMap/c-1");
        row.setTechnology("fiber");
        row.setPostcodePrefix("111");
        row.setMaxDownMbps(1000);
        row.setLastUpdate(OffsetDateTime.parse("2026-09-23T08:00:00Z"));
        assertThat(write(CoverageMapView.of(row))).isEqualTo("{\"id\":\"c-1\","
                + "\"href\":\"/tmf-api/serviceQualificationManagement/v4/coverageMap/c-1\","
                + "\"technology\":\"fiber\",\"postcodePrefix\":\"111\",\"maxDownMbps\":1000,"
                + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"CoverageMap\"}");
        row.setMaxUpMbps(500);
        row.setNote("pilot area");
        row.setAccessOwner("NORDACCESS");
        assertThat(write(CoverageMapView.of(row)))
                .contains("\"maxDownMbps\":1000,\"maxUpMbps\":500,\"note\":\"pilot area\",")
                .doesNotContain("accessOwner");
    }

    @Test
    void aPostedNumberStillArrivesAsTextSoTheServiceKeepsItsOwnRefusal() throws Exception {
        CoverageMapRequest req = mapper.readValue(
                "{\"technology\":\"fiber\",\"maxDownMbps\":1000,\"maxUpMbps\":\"500\","
                        + "\"unknown\":true}", CoverageMapRequest.class);
        assertThat(req.maxDownMbps()).isEqualTo("1000");
        assertThat(req.maxUpMbps()).isEqualTo("500");
        assertThat(req.note()).isNull();
    }

    @Test
    void aServiceableAreaRequestKeepsTheOfferingAsATree() throws Exception {
        ServiceableAreaRequest req = mapper.readValue(
                "{\"postcodePrefix\":\"111\",\"productOffering\":{\"id\":\"po-1\"},"
                        + "\"unknown\":1}", ServiceableAreaRequest.class);
        assertThat(req.productOffering().get("id").asText()).isEqualTo("po-1");
        assertThat(req.name()).isNull();
        assertThat(mapper.readValue("{\"productOffering\":\"not an object\"}",
                ServiceableAreaRequest.class).productOffering().isObject()).isFalse();
    }

    @Test
    void aServiceQualificationRequestTakesThePlaceWhereverTheCallerPutIt() throws Exception {
        ServiceQualificationRequest byCriteria = mapper.readValue(
                "{\"searchCriteria\":{\"place\":[{\"postCode\":\"1110\"}]},\"unknown\":1}",
                ServiceQualificationRequest.class);
        assertThat(byCriteria.searchCriteria()).isInstanceOf(Map.class);
        assertThat(byCriteria.place()).isNull();
        ServiceQualificationRequest fromDocument = ServiceQualificationRequest.of(
                Map.of("place", Map.of("postCode", "1110"), "technology", "vdsl"));
        assertThat(fromDocument.technology()).isEqualTo("vdsl");
        assertThat(fromDocument.serviceQualificationItem()).isNull();
    }
}
