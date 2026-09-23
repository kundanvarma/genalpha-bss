package com.bss.som;

import com.bss.som.dto.Characteristic;
import com.bss.som.dto.ComponentDescriptor;
import com.bss.som.dto.DealerDtos;
import com.bss.som.dto.ImportReport;
import com.bss.som.dto.IntentDtos;
import com.bss.som.dto.LineReceipts;
import com.bss.som.dto.LineRequests;
import com.bss.som.dto.Money;
import com.bss.som.dto.PartyRef;
import com.bss.som.dto.ServiceOrderView;
import com.bss.som.dto.ServiceRef;
import com.bss.som.dto.ServiceView;
import com.bss.som.dto.SpecRef;
import com.bss.som.dto.StandardFaceViews;
import com.bss.som.dto.TelesalesDtos;
import com.bss.som.dto.WholesaleDtos;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the
 * same order, absent keys absent, null keys null where they were null,
 * money at the entity's scale, a stored document verbatim. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-22T21:00:00.123456Z");

    @Test
    void serviceView_writesTheTmf638ShapeInOrder_leavingOffWhatTheMapLeftOff() throws Exception {
        ServiceView plain = new ServiceView("s1", "/tmf-api/serviceInventory/v4/service/s1", "Mobile 10 GB",
                "Mobile 10 GB — mobile service", "active", "mobile", AT.toString(), "so-1", null, null, null,
                List.of(ServiceView.ServiceRelationship.standalone("s1", "/tmf-api/serviceInventory/v4/service/s1")),
                List.of(new ServiceRef("s1", "/tmf-api/serviceInventory/v4/service/s1", "Mobile 10 GB",
                        "standalone — supports itself; not an invented dependency")),
                SpecRef.serviceSpec("mobile"),
                List.of(PartyRef.customerAt("p1"), PartyRef.serviceProvider("genalpha")),
                null,
                List.of(ServiceView.PlaceRef.serviceArea("genalpha")),
                List.of(ServiceView.ResourceRef.issued("a1", "+4790000001")),
                List.of(Characteristic.string("category", "mobile")),
                "Service");
        assertEquals("{\"id\":\"s1\",\"href\":\"/tmf-api/serviceInventory/v4/service/s1\",\"name\":\"Mobile 10 GB\","
                + "\"description\":\"Mobile 10 GB — mobile service\",\"state\":\"active\",\"category\":\"mobile\","
                + "\"startDate\":\"2026-09-22T21:00:00.123456Z\",\"serviceOrderId\":\"so-1\","
                + "\"serviceRelationship\":[{\"relationshipType\":\"standalone\",\"service\":{\"id\":\"s1\",\"href\":\"/tmf-api/serviceInventory/v4/service/s1\"}}],"
                + "\"supportingService\":[{\"id\":\"s1\",\"href\":\"/tmf-api/serviceInventory/v4/service/s1\",\"name\":\"Mobile 10 GB\",\"note\":\"standalone — supports itself; not an invented dependency\"}],"
                + "\"serviceSpecification\":{\"id\":\"svcspec-mobile\",\"href\":\"/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-mobile\",\"name\":\"mobile service\",\"version\":\"1.0\"},"
                + "\"relatedParty\":[{\"id\":\"p1\",\"href\":\"/tmf-api/party/v4/individual/p1\",\"role\":\"customer\"},{\"id\":\"op-genalpha\",\"href\":\"/tmf-api/party/v4/organization/op-genalpha\",\"role\":\"serviceProvider\"}],"
                + "\"place\":[{\"id\":\"sa-genalpha\",\"href\":\"/tmf-api/geographicSiteManagement/v4/geographicSite/sa-genalpha\",\"name\":\"service area\",\"role\":\"serviceArea\",\"@type\":\"RelatedPlaceRefOrValue\"}],"
                + "\"supportingResource\":[{\"id\":\"a1\",\"href\":\"/tmf-api/resourceInventoryManagement/v4/resource/a1\",\"value\":\"+4790000001\",\"@referredType\":\"Resource\"}],"
                + "\"serviceCharacteristic\":[{\"name\":\"category\",\"valueType\":\"string\",\"value\":\"mobile\"}],"
                + "\"@type\":\"Service\"}", json.writeValueAsString(plain));

        // paused, barred, on a path, on a slice: every optional key in its slot
        ObjectNode profile = json.createObjectNode();
        profile.put("outgoingBarred", true);
        ServiceView full = new ServiceView("s2", "/x/s2", "Fiber 1000", "Fiber 1000 — broadband service", "suspended",
                "broadband", AT.toString(), null, "vacation", AT.toString(),
                new ServiceView.Restriction("nonpayment", AT.toString(), profile),
                List.of(), List.of(), SpecRef.serviceSpec("broadband"), List.of(PartyRef.serviceProvider("t")),
                "fibre-route-1", List.of(ServiceView.PlaceRef.servingSite("fibre-route-1")),
                List.of(ServiceView.ResourceRef.serviceOrder("so-2")),
                List.of(Characteristic.string("deliveryPath", "fibre-route-1"),
                        Characteristic.dateTime("sliceUntil", AT.toString())), "Service");
        String out = json.writeValueAsString(full);
        assertTrue(out.contains("\"serviceOrderId\":null,\"suspendReason\":\"vacation\",\"resumeAt\":\"2026-09-22T21:00:00.123456Z\","
                + "\"restriction\":{\"reason\":\"nonpayment\",\"since\":\"2026-09-22T21:00:00.123456Z\",\"profile\":{\"outgoingBarred\":true}},"
                + "\"serviceRelationship\":[]"), out);
        assertTrue(out.contains("\"relatedParty\":[{\"id\":\"op-t\",\"href\":\"/tmf-api/party/v4/organization/op-t\",\"role\":\"serviceProvider\"}],"
                + "\"deliveryPath\":\"fibre-route-1\",\"place\":[{\"id\":\"path:fibre-route-1\","), out);
        assertTrue(out.contains("\"supportingResource\":[{\"id\":\"so-2\",\"href\":\"/tmf-api/serviceOrdering/v4/serviceOrder/so-2\",\"@referredType\":\"ServiceOrder\"}]"), out);
        assertTrue(out.contains("{\"name\":\"sliceUntil\",\"valueType\":\"dateTime\",\"value\":\"2026-09-22T21:00:00.123456Z\"}"), out);
    }

    @Test
    void serviceOrder_carriesThePostedItemsVerbatimAndTheDerivedOneInOrder() throws Exception {
        ObjectNode posted = (ObjectNode) json.readTree("{\"action\":\"add\",\"service\":{\"name\":\"x\"},\"note\":1}");
        posted.put("id", "1");
        posted.put("state", "acknowledged");
        ServiceOrderView external = new ServiceOrderView("o1", "/tmf-api/serviceOrdering/v4/serviceOrder/o1",
                "acknowledged", "external", AT.toString(), "external", "EXT-1", "1", "desc", null, List.of(posted),
                "ServiceOrder");
        assertEquals("{\"id\":\"o1\",\"href\":\"/tmf-api/serviceOrdering/v4/serviceOrder/o1\",\"state\":\"acknowledged\","
                + "\"category\":\"external\",\"orderDate\":\"2026-09-22T21:00:00.123456Z\",\"productOrderId\":\"external\","
                + "\"externalId\":\"EXT-1\",\"priority\":\"1\",\"description\":\"desc\","
                + "\"orderItem\":[{\"action\":\"add\",\"service\":{\"name\":\"x\"},\"note\":1,\"id\":\"1\",\"state\":\"acknowledged\"}],"
                + "\"@type\":\"ServiceOrder\"}", json.writeValueAsString(external));
        ServiceOrderView internal = new ServiceOrderView("o2", "/x/o2", "completed", "Mobile 10 GB", AT.toString(),
                "po-1", null, null, null, AT.toString(),
                List.of(json.valueToTree(ServiceOrderView.DerivedItem.add("completed", "Mobile 10 GB"))), "ServiceOrder");
        assertEquals("{\"id\":\"o2\",\"href\":\"/x/o2\",\"state\":\"completed\",\"category\":\"Mobile 10 GB\","
                + "\"orderDate\":\"2026-09-22T21:00:00.123456Z\",\"productOrderId\":\"po-1\","
                + "\"completionDate\":\"2026-09-22T21:00:00.123456Z\","
                + "\"orderItem\":[{\"id\":\"1\",\"state\":\"completed\",\"action\":\"add\",\"service\":{\"name\":\"Mobile 10 GB\"}}],"
                + "\"@type\":\"ServiceOrder\"}", json.writeValueAsString(internal));
    }

    @Test
    void lifecycleReceipts_areTheEventsEachPathWrote() throws Exception {
        assertEquals("{\"id\":\"s1\",\"name\":\"n\",\"state\":\"suspended\",\"reason\":\"vacation\","
                + "\"resumeAt\":\"2026-09-22T21:00:00.123456Z\",\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}",
                json.writeValueAsString(LineReceipts.ServiceStateReceipt.suspended("s1", "n", "suspended", "vacation",
                        AT.toString(), "p1")));
        assertEquals("{\"id\":\"s1\",\"name\":\"n\",\"state\":\"active\",\"resumedBy\":\"schedule\"}",
                json.writeValueAsString(LineReceipts.ServiceStateReceipt.resumed("s1", "n", "active", "schedule", null)));
        assertEquals("{\"id\":\"s1\",\"name\":\"n\",\"state\":\"terminated\",\"reason\":\"cease\",\"releasedNumber\":\"+47\","
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}",
                json.writeValueAsString(LineReceipts.ServiceStateReceipt.terminated("s1", "n", "terminated", "cease",
                        "+47", "p1")));
        // restrict: the event without the label, the answer with it
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("outgoingBarred", true);
        profile.put("emergencyWhitelist", true);
        LineReceipts.ServiceRestriction event = new LineReceipts.ServiceRestriction("s1", "n", "active", "nonpayment",
                profile, List.of(PartyRef.customer("p1")), null);
        assertEquals("{\"id\":\"s1\",\"name\":\"n\",\"state\":\"active\",\"reason\":\"nonpayment\","
                + "\"restrictionProfile\":{\"outgoingBarred\":true,\"emergencyWhitelist\":true},"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}", json.writeValueAsString(event));
        assertTrue(json.writeValueAsString(event.labelled()).endsWith(",\"@type\":\"ServiceRestriction\"}"));
        assertEquals("{\"id\":\"s1\",\"name\":\"n\",\"state\":\"active\",\"@type\":\"ServiceRestriction\"}",
                json.writeValueAsString(new LineReceipts.ServiceRestriction("s1", "n", "active", null, null, null, null)
                        .labelled()));
        assertEquals("{\"serviceId\":\"s1\",\"name\":\"n\",\"relatedParty\":[{\"id\":\"a\",\"role\":\"giver\"},"
                + "{\"id\":\"b\",\"role\":\"receiver\"}],\"@type\":\"ServiceTransfer\"}",
                json.writeValueAsString(new LineReceipts.ServiceTransfer("s1", "n", null,
                        List.of(PartyRef.of("a", "giver"), PartyRef.of("b", "receiver")), null).labelled()));
    }

    @Test
    void lineReceipts_sim_diagnosis_cpe() throws Exception {
        assertEquals("{\"serviceId\":\"s1\",\"iccid\":\"•••• 12345\",\"@type\":\"SimCard\"}",
                json.writeValueAsString(new LineReceipts.SimView("s1", "•••• 12345", null, "SimCard")));
        assertEquals("{\"serviceId\":\"s1\",\"iccid\":\"•••• 12345\",\"puk\":\"00000000\",\"@type\":\"SimCard\"}",
                json.writeValueAsString(new LineReceipts.SimView("s1", "•••• 12345", "00000000", "SimCard")));
        assertEquals("{\"status\":\"done\",\"@type\":\"SimPinReset\"}", json.writeValueAsString(LineReceipts.SimPinReset.done()));
        assertEquals("{\"serviceId\":\"s1\",\"reason\":\"lost\",\"oldSim\":{\"iccid\":\"•••• 1\",\"status\":\"blocked\"},"
                + "\"iccid\":\"•••• 2\",\"note\":\"n\",\"@type\":\"SimReplacement\"}",
                json.writeValueAsString(new LineReceipts.SimReplacement("s1", "lost",
                        new LineReceipts.SimReplacement.OldSim("•••• 1", "blocked"), "•••• 2", "n", "SimReplacement")));
        assertEquals("{\"serviceId\":\"s1\",\"name\":\"n\",\"verdict\":\"paused\","
                + "\"findings\":[{\"code\":\"paused\",\"severity\":\"cause\",\"message\":\"m\"}],\"@type\":\"ServiceDiagnosis\"}",
                json.writeValueAsString(new LineReceipts.ServiceDiagnosis("s1", "n", "paused",
                        List.of(LineReceipts.Finding.cause("paused", "m")), "ServiceDiagnosis")));
        assertEquals("{\"serviceId\":\"s1\",\"state\":\"online\",\"uptimeSeconds\":90000,\"firmware\":\"1.2\","
                + "\"firmwareOutdated\":false,\"wifiClients\":3,\"model\":\"HG8145\",\"serial\":\"X1\",\"lastSeen\":\"now\","
                + "\"@type\":\"CustomerPremisesEquipment\"}",
                json.writeValueAsString(new LineReceipts.CpeView("s1", "online", 90000, "1.2", false, 3, "HG8145", "X1",
                        "now", "CustomerPremisesEquipment")));
        assertEquals("{\"serviceId\":\"s1\",\"state\":\"rebooting\",\"said\":\"Restart sent to the router — it is back in about a minute.\",\"@type\":\"CpeRestart\"}",
                json.writeValueAsString(LineReceipts.CpeRestart.sent("s1")));
        assertEquals("{\"code\":\"503\",\"reason\":\"r\",\"@type\":\"Error\"}",
                json.writeValueAsString(LineReceipts.ErrorView.of(503, "r")));
        assertEquals("{\"number\":\"+47\",\"partyId\":\"p1\"}", json.writeValueAsString(new LineReceipts.NumberOwner("+47", "p1")));
        assertEquals("[{\"msisdn\":\"+4790\"}]", json.writeValueAsString(List.of(new LineReceipts.NumberOffer("+4790"))));
    }

    @Test
    void lineRequests_parseWhatTheDoorsRead_andIgnoreTheRest() throws Exception {
        LineRequests.SuspendRequest s = json.readValue("{\"reason\":\"vacation\",\"days\":\"14\",\"x\":1}",
                LineRequests.SuspendRequest.class);
        assertEquals(14L, s.days());
        assertNull(s.until());
        LineRequests.RestrictRequest r = json.readValue("{\"profile\":{\"outgoingBarred\":true,\"emergencyWhitelist\":false}}",
                LineRequests.RestrictRequest.class);
        assertEquals(false, r.profile().get("emergencyWhitelist"));
        assertNull(r.reason());
        assertEquals("7", json.readValue("{\"nextValue\":7,\"prefix\":\"+47\"}", LineRequests.PoolRequest.class)
                .nextValue().toString());
    }

    @Test
    void standardFaces_testSpecPoolResourceMonitor() throws Exception {
        JsonNode spec = json.readTree("{\"id\":\"diagnose\",\"href\":\"/h\"}");
        JsonNode findings = json.readTree("[{\"code\":\"allClear\",\"severity\":\"info\",\"message\":\"m\"}]");
        assertEquals("{\"id\":\"t1\",\"href\":\"/tmf-api/serviceTestManagement/v4/serviceTest/t1\",\"name\":\"diagnose s1\","
                + "\"relatedService\":{\"id\":\"s1\",\"href\":\"/tmf-api/serviceInventory/v4/service/s1\"},"
                + "\"testSpecification\":{\"id\":\"diagnose\",\"href\":\"/h\"},\"state\":\"completed\",\"verdict\":\"allClear\","
                + "\"testMeasure\":[{\"code\":\"allClear\",\"severity\":\"info\",\"message\":\"m\"}],"
                + "\"createdAt\":\"2026-09-22T21:00:00.123456Z\",\"@type\":\"ServiceTest\"}",
                json.writeValueAsString(new StandardFaceViews.ServiceTestView("t1",
                        "/tmf-api/serviceTestManagement/v4/serviceTest/t1", "diagnose s1", ServiceRef.inventory("s1"),
                        spec, "completed", "allClear", findings, AT, "ServiceTest")));
        assertEquals("{\"id\":\"diagnose\",\"href\":\"/tmf-api/serviceTestManagement/v4/serviceTestSpecification/diagnose\",\"name\":\"diagnose triage\"}",
                json.writeValueAsString(SpecRef.diagnose()));
        assertEquals("{\"id\":\"p1\",\"name\":\"numbers\",\"resourceType\":\"msisdn\",\"prefix\":\"+47\",\"@type\":\"ResourcePool\"}",
                json.writeValueAsString(StandardFaceViews.ResourcePoolView.of("p1", "numbers", "msisdn", "+47")));
        assertTrue(json.writeValueAsString(StandardFaceViews.ResourcePoolView.facts("p1", "numbers", "msisdn", "+47", 12))
                .startsWith("{\"id\":\"p1\",\"name\":\"numbers\",\"resourceType\":\"msisdn\",\"prefix\":\"+47\",\"issuedCounter\":12,\"note\":\"this pool"));
        assertEquals("{\"id\":\"a1\",\"href\":\"/tmf-api/resourceInventoryManagement/v4/resource/a1\",\"name\":\"+47\",\"value\":\"+47\","
                + "\"resourceStatus\":\"assigned\",\"poolId\":\"pool-1\",\"relatedService\":{\"id\":\"s1\"},"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],\"@type\":\"Resource\"}",
                json.writeValueAsString(StandardFaceViews.ResourceView.assigned("a1", "+47", "pool-1", "s1", "p1")));
        assertEquals("{\"id\":\"quarantine-+47\",\"href\":\"/tmf-api/resourceInventoryManagement/v4/resource/quarantine-+47\","
                + "\"name\":\"+47\",\"value\":\"+47\",\"resourceStatus\":\"quarantined\",\"@type\":\"Resource\"}",
                json.writeValueAsString(StandardFaceViews.ResourceView.quarantined("+47")));
        assertEquals("{\"id\":\"svcspec-tv\",\"href\":\"/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-tv\","
                + "\"name\":\"tv service\",\"version\":\"1.0\",\"lifecycleStatus\":\"active\",\"@type\":\"ServiceSpecification\"}",
                json.writeValueAsString(StandardFaceViews.ServiceSpecView.of("tv")));
        assertEquals("{\"method\":\"POST\",\"to\":\"/x\"}",
                json.writeValueAsString(new StandardFaceViews.MonitorRequest("POST", "/x", null)));
        assertEquals("{\"statusCode\":201,\"body\":{\"id\":\"s1\"}}",
                json.writeValueAsString(new StandardFaceViews.MonitorResponse(201, json.readTree("{\"id\":\"s1\"}"))));
        StandardFaceViews.ServiceTestRequest req = json.readValue(
                "{\"relatedService\":{\"id\":\"s1\",\"href\":\"/h\",\"extra\":true},\"testSpecification\":{\"id\":\"diagnose\"},\"foo\":1}",
                StandardFaceViews.ServiceTestRequest.class);
        assertEquals("s1", req.serviceId());
        assertEquals("diagnose", req.testSpecification().get("id").asText());
    }

    @Test
    void dealerAndTelesales_moneyAtStoredScale_nullsWhereTheMapWroteThem() throws Exception {
        assertEquals("{\"id\":\"d1\",\"dealerOrgId\":\"org-1\",\"name\":\"Store\",\"commission\":{\"value\":25.00,\"unit\":\"EUR\"},\"@type\":\"DealerAgreement\"}",
                json.writeValueAsString(new DealerDtos.DealerAgreementView("d1", "org-1", "Store",
                        new Money(new BigDecimal("25.00"), "EUR"), "DealerAgreement")));
        assertEquals("{\"id\":\"k1\",\"activationCode\":\"ABCD2345\",\"iccid\":\"8946\",\"store\":null,\"status\":\"available\","
                + "\"activatedAt\":null,\"@type\":\"StarterKit\"}",
                json.writeValueAsString(new DealerDtos.StarterKitView("k1", "ABCD2345", "8946", null, "available", null, "StarterKit")));
        assertEquals("{\"id\":\"c1\",\"store\":null,\"offeringName\":\"Mobile\",\"device\":null,\"amount\":{\"value\":25.00,\"unit\":\"EUR\"},"
                + "\"status\":\"pending\",\"reason\":null,\"accruedAt\":\"2026-09-22T21:00:00.123456Z\","
                + "\"hardensAt\":\"2026-09-22T21:00:00.123456Z\",\"@type\":\"CommissionEntry\"}",
                json.writeValueAsString(new DealerDtos.CommissionView("c1", null, "Mobile", null,
                        new Money(new BigDecimal("25.00"), "EUR"), "pending", null, AT.toString(), AT.toString(),
                        "CommissionEntry")));
        assertEquals("{\"dealerOrgId\":\"org-1\",\"store\":\"Oslo\",\"activations\":2,\"commission\":50.00,\"unit\":\"EUR\",\"rank\":1}",
                json.writeValueAsString(new DealerDtos.LeaderboardRow("org-1", "Oslo", 2, new BigDecimal("50.00"), "EUR", 1)));
        assertEquals("{\"productOrderId\":\"po-1\",\"customerId\":\"p1\"}",
                json.writeValueAsString(new DealerDtos.SaleReceipt("po-1", "p1")));
        assertEquals("{\"productOrderId\":\"po-1\",\"activated\":true,\"commission\":[]}",
                json.writeValueAsString(new DealerDtos.OrderStatus("po-1", true, List.of())));
        // the agreement form: a commission value posted as a string or a number
        DealerDtos.AgreementRequest a = json.readValue("{\"dealerOrgId\":\"org-1\",\"commission\":{\"value\":\"25\",\"unit\":\"NOK\"}}",
                DealerDtos.AgreementRequest.class);
        assertEquals(new BigDecimal("25"), a.commission().value());
        assertEquals(3, json.readValue("{\"count\":\"3\"}", DealerDtos.KitBatchRequest.class).count());
        assertEquals("{\"offerId\":\"o1\",\"status\":\"offered\",\"expiresAt\":\"t\"}",
                json.writeValueAsString(new TelesalesDtos.OfferReceipt("o1", "offered", "t", null, null)));
        assertEquals("{\"offerId\":\"o1\",\"status\":\"offered\",\"expiresAt\":\"t\",\"confirmToken\":\"ABC\",\"prospect\":true}",
                json.writeValueAsString(new TelesalesDtos.OfferReceipt("o1", "offered", "t", "ABC", true)));
        assertEquals("{\"segment\":\"s\",\"entries\":[{\"partyId\":\"p\",\"name\":\"A B\",\"phone\":\"+47\",\"email\":null,\"consent\":\"c\"}],"
                + "\"reservedExcluded\":1,\"unwashedExcluded\":0}",
                json.writeValueAsString(new TelesalesDtos.DialList("s",
                        List.of(new TelesalesDtos.DialEntry("p", "A B", "+47", null, "c")), 1, 0)));
    }

    @Test
    void intents_reportInOrder_bareExpressionAccepted() throws Exception {
        IntentDtos.IntentReport report = new IntentDtos.IntentReport(true, null, "edge:gpu-1",
                List.of(new IntentDtos.ProposedItem("5g-slice", "Stadium 5G Slice", "r")),
                new IntentDtos.Expectation(8, 2000, true));
        assertEquals("{\"feasible\":true,\"deliveryPoint\":\"edge:gpu-1\","
                + "\"proposedItems\":[{\"service\":\"5g-slice\",\"offeringName\":\"Stadium 5G Slice\",\"reason\":\"r\"}],"
                + "\"expectation\":{\"latencyMs\":8,\"bandwidthMbps\":2000,\"slaBacked\":true}}", json.writeValueAsString(report));
        assertEquals("{\"feasible\":false,\"reason\":\"physics\"}", json.writeValueAsString(IntentDtos.IntentReport.infeasible("physics")));
        assertEquals("{\"id\":\"i1\",\"href\":\"/tmf-api/intentManagement/v4/intent/i1\",\"name\":\"n\",\"status\":\"infeasible\","
                + "\"expression\":{\"place\":\"arena\",\"latencyMs\":8,\"bandwidthMbps\":2000},"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],\"intentReport\":{\"feasible\":false,\"reason\":\"physics\"},\"@type\":\"Intent\"}",
                json.writeValueAsString(new IntentDtos.IntentView("i1", "/tmf-api/intentManagement/v4/intent/i1", "n", null,
                        "infeasible", new IntentDtos.ExpressionView("arena", 8, 2000, null, null, null),
                        List.of(PartyRef.customer("p1")), json.valueToTree(IntentDtos.IntentReport.infeasible("physics")),
                        "Intent")));
        IntentDtos.IntentRequest nested = json.readValue("{\"name\":\"n\",\"expression\":{\"place\":\"a\",\"latencyMs\":\"8\"}}",
                IntentDtos.IntentRequest.class);
        assertEquals(8L, nested.expression().latencyMs());
        IntentDtos.IntentRequest bare = json.readValue("{\"name\":\"n\",\"place\":\"a\",\"latencyMs\":50,\"bandwidthMbps\":500}",
                IntentDtos.IntentRequest.class);
        assertNull(bare.expression());
        assertEquals(50L, json.convertValue(bare.extensions(), IntentDtos.Expression.class).latencyMs());
    }

    @Test
    void wholesale_bothSides_andTheDescriptor_andImport() throws Exception {
        assertEquals("{\"@type\":\"WholesaleSettlement\",\"periodType\":\"month\","
                + "\"owner\":[{\"accessOwner\":\"NORDACCESS\",\"accessLayer\":null,\"activeLines\":2,\"ratePerLine\":0.0,\"monthlyOwed\":0.0,\"currency\":\"EUR\"},"
                + "{\"accessOwner\":\"FJORDFIBER\",\"accessLayer\":\"L3\",\"activeLines\":1,\"ratePerLine\":22.5,\"monthlyOwed\":22.5,"
                + "\"retailMonthlyPerLine\":59.0,\"marginPerLine\":36.5,\"currency\":\"EUR\"}],"
                + "\"totalActiveLines\":3,\"totalMonthlyOwed\":22.5,\"totalMonthlyMargin\":36.5,\"currency\":\"EUR\"}",
                json.writeValueAsString(new WholesaleDtos.WholesaleSettlement("WholesaleSettlement", "month",
                        List.of(new WholesaleDtos.OwnerStatement("NORDACCESS", null, 2, 0.0, 0.0, null, null, "EUR"),
                                new WholesaleDtos.OwnerStatement("FJORDFIBER", "L3", 1, 22.5, 22.5, 59.0, 36.5, "EUR")),
                        3, 22.5, 36.5, "EUR")));
        assertEquals("{\"id\":\"w1\",\"productOrderId\":\"po\",\"serviceId\":\"s\",\"accessOwner\":\"O\",\"accessLayer\":null,"
                + "\"bandwidthMbps\":1000,\"postCode\":\"0150\",\"state\":\"active\",\"externalId\":null,"
                + "\"activatedAt\":\"2026-09-22T21:00:00.123456Z\",\"createdAt\":\"2026-09-22T21:00:00.123456Z\",\"@type\":\"WholesaleAccessOrder\"}",
                json.writeValueAsString(new WholesaleDtos.WholesaleAccessOrderView("w1", "po", "s", "O", null, 1000, "0150",
                        "active", null, AT, AT, "WholesaleAccessOrder")));
        assertEquals("{\"@type\":\"WholesaleProviderSettlement\",\"periodType\":\"month\","
                + "\"retailer\":[{\"retailer\":\"genalpha\",\"line\":[{\"accessLayer\":\"L2\",\"activeLines\":1,\"ratePerLine\":15.0,\"amount\":15.0}],"
                + "\"totalMonthlyCharge\":15.0,\"currency\":\"EUR\"}],\"totalMonthlyRevenue\":15.0,\"currency\":\"EUR\"}",
                json.writeValueAsString(new WholesaleDtos.ProviderSettlement("WholesaleProviderSettlement", "month",
                        List.of(new WholesaleDtos.RetailerStatement("genalpha",
                                List.of(new WholesaleDtos.SettlementLine("L2", 1, 15.0, 15.0)), 15.0, "EUR")), 15.0, "EUR")));
        assertEquals("{\"id\":\"o1\",\"state\":\"acknowledged\",\"@type\":\"ServiceOrder\"}",
                json.writeValueAsString(new WholesaleDtos.SonataOrderAck("o1", "acknowledged", "ServiceOrder")));
        WholesaleDtos.SonataOrderRequest sonata = json.readValue("{\"externalId\":\"b\",\"buyerId\":\"genalpha\","
                + "\"serviceOrderItem\":[{\"action\":\"add\",\"service\":{\"serviceCharacteristic\":["
                + "{\"name\":\"accessLayer\",\"value\":\"L2\"},{\"name\":\"bandwidthMbps\",\"value\":1000},{\"name\":\"postCode\",\"value\":\"0150\"}]}}]}",
                WholesaleDtos.SonataOrderRequest.class);
        assertEquals(3, sonata.characteristics().size());
        assertTrue(sonata.characteristics().get(1).value() instanceof Number);
        assertEquals("s", json.readValue("{\"sonataOrderId\":\"s\",\"id\":\"i\"}", WholesaleDtos.SonataNotification.class).orderId());
        assertEquals("i", json.readValue("{\"id\":\"i\"}", WholesaleDtos.SonataNotification.class).orderId());
        assertEquals("{\"component\":\"service-orchestration\",\"meaning\":\"m\",\"manages\":[\"Service\"],\"events\":[\"E\"],"
                + "\"topic\":\"bss.som.events\",\"routes\":[\"GET /x\"],\"@type\":\"GenAlphaComponent\"}",
                json.writeValueAsString(new ComponentDescriptor("service-orchestration", "m", List.of("Service"),
                        List.of("E"), "bss.som.events", List.of("GET /x"), "GenAlphaComponent")));
        assertEquals("{\"imported\":true,\"serviceId\":\"s1\",\"name\":\"n\",\"msisdn\":\"\"}",
                json.writeValueAsString(ImportReport.Imported.of("s1", "n", null)));
        assertEquals("{\"imported\":false,\"reason\":\"already imported\"}", json.writeValueAsString(ImportReport.Skipped.already()));
        assertEquals("{\"error\":\"e\"}", json.writeValueAsString(new ImportReport.Refused("e")));
    }
}
