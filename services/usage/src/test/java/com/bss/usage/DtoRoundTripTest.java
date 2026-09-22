package com.bss.usage;

import com.bss.usage.dto.Amount;
import com.bss.usage.dto.AutoTopupPolicyRequest;
import com.bss.usage.dto.AutoTopupPolicyView;
import com.bss.usage.dto.ConsumptionBucket;
import com.bss.usage.dto.ConsumptionReport;
import com.bss.usage.dto.CycleCloseReceipt;
import com.bss.usage.dto.DataGift;
import com.bss.usage.dto.DeviceDetectionReceipt;
import com.bss.usage.dto.ImsiRangeView;
import com.bss.usage.dto.Money;
import com.bss.usage.dto.MvnoStatement;
import com.bss.usage.dto.OcsSubscriber;
import com.bss.usage.dto.PoolMemberPatch;
import com.bss.usage.dto.PoolMemberView;
import com.bss.usage.dto.PoolView;
import com.bss.usage.dto.PrepayBucketView;
import com.bss.usage.dto.PriorityUsageReceipt;
import com.bss.usage.dto.ProviderLedgerView;
import com.bss.usage.dto.ProviderLine;
import com.bss.usage.dto.ProviderRateCardView;
import com.bss.usage.dto.ProviderSettlement;
import com.bss.usage.dto.ProviderUsageResult;
import com.bss.usage.dto.RateUsageRequest;
import com.bss.usage.dto.RatedChargeView;
import com.bss.usage.dto.Receipt;
import com.bss.usage.dto.RelatedPartyRef;
import com.bss.usage.dto.SigscaleRelayReceipt;
import com.bss.usage.dto.SimulateWholesaleRequest;
import com.bss.usage.dto.SpendMeterView;
import com.bss.usage.dto.SpendPolicyPatch;
import com.bss.usage.dto.SpendVerdict;
import com.bss.usage.dto.Tier;
import com.bss.usage.dto.TimePeriod;
import com.bss.usage.dto.TravelPassView;
import com.bss.usage.dto.UnitValue;
import com.bss.usage.dto.UsageAllowanceRequest;
import com.bss.usage.dto.UsageAllowanceView;
import com.bss.usage.dto.UsageSpecificationView;
import com.bss.usage.dto.UsageThresholdNotification;
import com.bss.usage.dto.UsageView;
import com.bss.usage.dto.WholesaleLedgerView;
import com.bss.usage.dto.WholesaleRateCardView;
import com.bss.usage.dto.WholesaleRerated;
import com.bss.usage.dto.WholesaleSettlement;
import com.bss.usage.dto.WholesaleSimulation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the
 * same order, quantities with the scale the entity stores, absent keys
 * absent, null keys null where the map wrote null. Pure Jackson, configured
 * as Spring Boot configures it — no context, no database. The stored forms
 * (a usage document, a tier table) parse back into the records that read
 * them, and the request records keep absent-vs-null where a PATCH needs it.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-22T09:08:12.169178Z");
    private static final BigDecimal GB3 = new BigDecimal("8.000");

    // ---- atoms ----

    @Test
    void money_keepsTheEntityScale_andWritesAMissingUnitAsNull() throws Exception {
        assertEquals("{\"value\":0,\"unit\":null}", json.writeValueAsString(new Money(BigDecimal.ZERO, null)));
        assertEquals("{\"value\":5.75,\"unit\":\"EUR\"}", json.writeValueAsString(new Money(new BigDecimal("5.75"), "EUR")));
        assertEquals("{\"value\":250,\"unit\":\"NOK\"}", json.writeValueAsString(new Money(new BigDecimal("250"), "NOK")));
        assertEquals("{\"value\":10.000,\"units\":\"GB\"}", json.writeValueAsString(new UnitValue(new BigDecimal("10.000"), "GB")));
        assertEquals("{\"amount\":6.5,\"units\":\"GB\"}", json.writeValueAsString(new Amount(6.5, "GB")));
        assertEquals("{\"amount\":10.0,\"units\":\"GB\"}", json.writeValueAsString(new Amount(10.0, "GB")));
        assertEquals("{\"id\":\"p1\",\"role\":\"customer\"}", json.writeValueAsString(RelatedPartyRef.customer("p1")));
        assertEquals("{\"startDateTime\":\"2026-09-01\"}", json.writeValueAsString(TimePeriod.from("2026-09-01")));
        assertEquals("{\"status\":\"accepted\"}", json.writeValueAsString(Receipt.ACCEPTED));
    }

    @Test
    void tier_storesAsTheArrayItAlwaysWas_andReadsBackWithItsExtras() throws Exception {
        List<Tier> tiers = List.of(Tier.of(BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("2.0")),
                Tier.of(new BigDecimal("5"), null, new BigDecimal("1")));
        assertEquals("[{\"valueFrom\":0,\"valueTo\":5,\"price\":2.0},{\"valueFrom\":5,\"price\":1}]",
                json.writeValueAsString(tiers));
        List<Tier> stored = json.readValue("[{\"valueFrom\":1,\"valueTo\":5,\"price\":2.50,\"note\":\"first\"},{\"valueFrom\":6}]",
                new TypeReference<List<Tier>>() { });
        assertEquals(new BigDecimal("2.50"), stored.get(0).price());
        assertEquals("first", stored.get(0).extensions().get("note"));
        assertNull(stored.get(1).valueTo());
        assertNull(stored.get(1).price());
        assertEquals("[{\"valueFrom\":1,\"valueTo\":5,\"price\":2.50,\"note\":\"first\"},{\"valueFrom\":6}]",
                json.writeValueAsString(stored));
    }

    // ---- TMF635 / TMF677 ----

    @Test
    void usageView_laysTheRecordOverTheStoredDocument() throws Exception {
        JsonNode stored = json.readTree("{\"usageType\":\"snap data\",\"usageCharacteristic\":[{\"value\":9.5,\"units\":\"GB\"}],"
                + "\"productOffering\":{\"id\":\"po-1\"},\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],\"extra\":{\"k\":1}}");
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("productOffering", stored.get("productOffering"));
        extensions.put("extra", stored.get("extra"));
        UsageView view = new UsageView("u1", "/tmf-api/usageManagement/v4/usage/u1", "snap data", AT,
                stored.get("usageCharacteristic"), List.of(RelatedPartyRef.customer("p1")), "received", "world-1",
                null, json.createObjectNode(), "Usage", null, extensions);
        assertEquals("{\"id\":\"u1\",\"href\":\"/tmf-api/usageManagement/v4/usage/u1\",\"usageType\":\"snap data\","
                + "\"usageDate\":\"2026-09-22T09:08:12.169178Z\",\"usageCharacteristic\":[{\"value\":9.5,\"units\":\"GB\"}],"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],\"status\":\"received\",\"zone\":\"world-1\","
                + "\"usageSpecification\":{},\"@type\":\"Usage\",\"productOffering\":{\"id\":\"po-1\"},\"extra\":{\"k\":1}}",
                json.writeValueAsString(view));
        // the zone announcement rides only the ingest answer; a bare record synthesises its characteristic
        assertTrue(json.writeValueAsString(view.withZoneEntered()).endsWith("\"zoneEntered\":true,\"productOffering\":{\"id\":\"po-1\"},\"extra\":{\"k\":1}}"));
        UsageView bare = new UsageView("u2", "/x/u2", null, AT, new UnitValue(BigDecimal.ZERO, "unit"), null,
                "received", null, new BigDecimal("1.000"), json.createObjectNode(), "Usage", null, null);
        assertEquals("{\"id\":\"u2\",\"href\":\"/x/u2\",\"usageDate\":\"2026-09-22T09:08:12.169178Z\","
                + "\"usageCharacteristic\":{\"value\":0,\"units\":\"unit\"},\"status\":\"received\",\"pooledValue\":1.000,"
                + "\"usageSpecification\":{},\"@type\":\"Usage\"}", json.writeValueAsString(bare));
    }

    @Test
    void usageSpecification_writesTheServerKeysThenTheDocument() throws Exception {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("units", json.getNodeFactory().textNode("GB"));
        extensions.put("description", json.getNodeFactory().textNode("snapshot"));
        assertEquals("{\"id\":\"s1\",\"href\":\"/x/s1\",\"name\":null,\"lastUpdate\":\"2026-09-22T09:08:12.169178Z\","
                + "\"@type\":\"UsageSpecification\",\"units\":\"GB\",\"description\":\"snapshot\"}",
                json.writeValueAsString(new UsageSpecificationView("s1", "/x/s1", null, AT, "UsageSpecification", extensions)));
    }

    @Test
    void allowance_leavesOffTiersAndBoostUnlessSet() throws Exception {
        JsonNode offering = json.readTree("{\"id\":\"po-1\",\"name\":\"Plan\"}");
        UsageAllowanceView flat = new UsageAllowanceView("a1", "/x/a1", "EU roaming data", offering,
                new UnitValue(new BigDecimal("10.000"), "GB"), new Money(new BigDecimal("2.5000"), "EUR"), null, null,
                "UsageAllowance");
        assertEquals("{\"id\":\"a1\",\"href\":\"/x/a1\",\"usageType\":\"EU roaming data\",\"productOffering\":{\"id\":\"po-1\",\"name\":\"Plan\"},"
                + "\"allowance\":{\"value\":10.000,\"units\":\"GB\"},\"overagePrice\":{\"value\":2.5000,\"unit\":\"EUR\"},\"@type\":\"UsageAllowance\"}",
                json.writeValueAsString(flat));
        UsageAllowanceView tiered = new UsageAllowanceView("a2", "/x/a2", "Streaming hours", offering,
                new UnitValue(new BigDecimal("5.000"), "h"), new Money(new BigDecimal("1.0000"), "EUR"),
                List.of(Tier.of(new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("2.0"))), Boolean.TRUE, "UsageAllowance");
        assertTrue(json.writeValueAsString(tiered).contains("\"overageTier\":[{\"valueFrom\":1,\"valueTo\":5,\"price\":2.0}],\"boost\":true,\"@type\""));
        UsageAllowanceRequest request = json.readValue("{\"productOffering\":{\"id\":\"po-9\"},\"usageType\":\"x\","
                + "\"allowance\":{\"value\":10,\"units\":\"GB\"},\"overagePrice\":{\"unit\":\"EUR\",\"value\":1.5},\"boost\":\"true\","
                + "\"overageTier\":[{\"valueFrom\":0,\"valueTo\":5,\"price\":2.0}],\"unknown\":1}", UsageAllowanceRequest.class);
        assertEquals("po-9", request.offeringId());
        assertEquals(Boolean.TRUE, request.boost());
        assertEquals("[{\"valueFrom\":0,\"valueTo\":5,\"price\":2.0}]", json.writeValueAsString(request.overageTier()));
    }

    @Test
    void ratedCharge_andTheReport() throws Exception {
        assertEquals("{\"ownerPartyId\":\"p1\",\"name\":\"EU roaming data overage: 2.3 GB over 10 GB included\",\"amount\":{\"value\":5.75,\"unit\":\"EUR\"}}",
                json.writeValueAsString(new RatedChargeView("p1", "EU roaming data overage: 2.3 GB over 10 GB included",
                        new Money(new BigDecimal("5.75"), "EUR"))));
        ConsumptionReport report = new ConsumptionReport("ucr-p1", "/tmf-api/usageConsumption/v4/usageConsumptionReport/ucr-p1",
                "usageConsumptionReport-p1", "2026-09-22T09:08:12.169178Z", "UsageConsumptionReport",
                List.of(RelatedPartyRef.customer("p1")), TimePeriod.from("2026-09-01"),
                List.of(new ConsumptionBucket("bkt-1", "snap data", null, GB3, "GB", new BigDecimal("10.000")),
                        new ConsumptionBucket("bkt-2", "snap data — world-1", "world-1", new BigDecimal("9.500"), "GB", null)),
                null);
        assertEquals("{\"id\":\"ucr-p1\",\"href\":\"/tmf-api/usageConsumption/v4/usageConsumptionReport/ucr-p1\","
                + "\"name\":\"usageConsumptionReport-p1\",\"effectiveDate\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"UsageConsumptionReport\","
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],\"period\":{\"startDateTime\":\"2026-09-01\"},"
                + "\"bucket\":[{\"id\":\"bkt-1\",\"name\":\"snap data\",\"usedValue\":8.000,\"units\":\"GB\",\"allowedValue\":10.000},"
                + "{\"id\":\"bkt-2\",\"name\":\"snap data — world-1\",\"zone\":\"world-1\",\"usedValue\":9.500,\"units\":\"GB\"}]}",
                json.writeValueAsString(report));
        assertTrue(report.hasBucket("bkt-2"));
        assertFalse(report.hasBucket("bkt-9"));
    }

    @Test
    void smallReceipts() throws Exception {
        assertEquals("{\"period\":\"2026-09-01\",\"rolledBuckets\":3}", json.writeValueAsString(new CycleCloseReceipt("2026-09-01", 3)));
        assertEquals("{\"id\":\"g1\",\"giver\":{\"id\":\"p1\",\"name\":\"Paula Family\"},\"receiver\":{\"id\":\"p2\",\"name\":\"+4799\"},"
                + "\"amount\":2,\"units\":\"GB\",\"usageType\":\"Mobile data\"}",
                json.writeValueAsString(new DataGift("g1", new DataGift.PartyName("p1", "Paula Family"),
                        new DataGift.PartyName("p2", "+4799"), new BigDecimal("2"), "GB", "Mobile data")));
        assertEquals("{\"id\":\"b1\",\"partyId\":\"p1\",\"usageType\":\"tp data\",\"zone\":\"world-1\",\"amountGB\":5,"
                + "\"validFor\":{\"startDateTime\":\"2026-09-01T00:00Z\",\"endDateTime\":\"2026-09-08T00:00Z\"},\"@type\":\"TravelPass\"}",
                json.writeValueAsString(new TravelPassView("b1", "p1", "tp data", "world-1", new BigDecimal("5"),
                        new TimePeriod("2026-09-01T00:00Z", "2026-09-08T00:00Z"), "TravelPass")));
        assertEquals("{\"status\":\"recorded\",\"partyId\":\"p1\",\"deviceModel\":\"iPhone 15\"}",
                json.writeValueAsString(new DeviceDetectionReceipt("recorded", "p1", "iPhone 15")));
        assertEquals("{\"status\":\"ignored\"}", json.writeValueAsString(PriorityUsageReceipt.IGNORED));
        assertEquals("{\"status\":\"rated\",\"amount\":1.00,\"unit\":\"NOK\"}",
                json.writeValueAsString(PriorityUsageReceipt.rated(new BigDecimal("1.00"), "NOK")));
        assertEquals("{\"status\":\"ignored\",\"reason\":\"no event array\"}", json.writeValueAsString(SigscaleRelayReceipt.ignored("no event array")));
        assertEquals("{\"status\":\"accepted\",\"relayed\":[]}", json.writeValueAsString(SigscaleRelayReceipt.accepted(List.of())));
    }

    @Test
    void usageThresholdNotification_readsTheOcsDoor_andWritesTheSigscaleTranslation() throws Exception {
        UsageThresholdNotification n = json.readValue("{\"partyId\":\"p1\",\"percentUsed\":100,\"threshold\":100,\"windowId\":\"w1\","
                + "\"remainingGB\":\"1.5\",\"foreign\":true}", UsageThresholdNotification.class);
        assertEquals(new BigDecimal("100"), n.percentUsed());
        assertEquals("100", n.threshold().toString());
        assertEquals(new BigDecimal("1.5"), n.remainingGB());
        assertNull(n.tenantId());
        UsageThresholdNotification translated = new UsageThresholdNotification("genalpha", "p1", "svc", "RG-DATA-10", "10 GB counter data",
                BigDecimal.valueOf(10.0), BigDecimal.valueOf(8.5), BigDecimal.valueOf(1.5), BigDecimal.valueOf(85L),
                BigDecimal.valueOf(0.8), "GB", "sigscale", null);
        assertEquals("{\"tenantId\":\"genalpha\",\"partyId\":\"p1\",\"serviceId\":\"svc\",\"ratePlanId\":\"RG-DATA-10\","
                + "\"bucketName\":\"10 GB counter data\",\"totalGB\":10.0,\"usedGB\":8.5,\"remainingGB\":1.5,\"percentUsed\":85,"
                + "\"threshold\":0.8,\"units\":\"GB\",\"source\":\"sigscale\"}", json.writeValueAsString(translated));
    }

    // ---- policy ----

    @Test
    void spendMeter_threeFaces_andTheVerdict() throws Exception {
        SpendMeterView spend = new SpendMeterView("p1", "spend", false, null, null, null, new BigDecimal("80"), true,
                new Money(BigDecimal.ZERO, null), false, null, "2026-09-01", "SpendMeter");
        assertEquals("{\"partyId\":\"p1\",\"meterType\":\"spend\",\"enabled\":false,\"notifyAtPct\":80,\"blockOnBreach\":true,"
                + "\"accrued\":{\"value\":0,\"unit\":null},\"blocked\":false,\"period\":\"2026-09-01\",\"@type\":\"SpendMeter\"}",
                json.writeValueAsString(spend));
        SpendMeterView content = new SpendMeterView("p1", "content", true, true, new Money(new BigDecimal("250"), "NOK"), null,
                new BigDecimal("80.00"), true, new Money(new BigDecimal("0.00"), "NOK"), false, null, "2026-09-01", "SpendMeter");
        assertEquals("{\"partyId\":\"p1\",\"meterType\":\"content\",\"enabled\":true,\"barred\":true,"
                + "\"lowestSelectableLimit\":{\"value\":250,\"unit\":\"NOK\"},\"notifyAtPct\":80.00,\"blockOnBreach\":true,"
                + "\"accrued\":{\"value\":0.00,\"unit\":\"NOK\"},\"blocked\":false,\"period\":\"2026-09-01\",\"@type\":\"SpendMeter\"}",
                json.writeValueAsString(content));
        SpendMeterView roaming = new SpendMeterView("p1", "roaming", true, null, null, new Money(new BigDecimal("50"), "EUR"),
                new BigDecimal("80"), true, new Money(new BigDecimal("55.00"), "EUR"), true, false, "2026-09-01", "SpendMeter");
        assertEquals("{\"partyId\":\"p1\",\"meterType\":\"roaming\",\"enabled\":true,\"limit\":{\"value\":50,\"unit\":\"EUR\"},"
                + "\"notifyAtPct\":80,\"blockOnBreach\":true,\"accrued\":{\"value\":55.00,\"unit\":\"EUR\"},\"blocked\":true,"
                + "\"continueElected\":false,\"period\":\"2026-09-01\",\"@type\":\"SpendMeter\"}", json.writeValueAsString(roaming));
        assertEquals("{\"accepted\":false,\"meter\":[]}", json.writeValueAsString(new SpendVerdict(false, List.of())));
    }

    @Test
    void spendPolicyPatch_keepsAbsentApartFromNull() throws Exception {
        SpendPolicyPatch absent = json.readValue("{\"enabled\":true}", SpendPolicyPatch.class);
        assertFalse(absent.hasLimit());
        assertEquals(Boolean.TRUE, absent.enabled());
        SpendPolicyPatch cleared = json.readValue("{\"limit\":null,\"blockOnBreach\":false}", SpendPolicyPatch.class);
        assertTrue(cleared.hasLimit());
        assertNull(cleared.limitValue());
        SpendPolicyPatch set = json.readValue("{\"limit\":300,\"currency\":\"NOK\",\"notifyAtPct\":50}", SpendPolicyPatch.class);
        assertEquals(new BigDecimal("300"), set.limitValue());
        assertEquals(new BigDecimal("50"), set.notifyAtPct());
        PoolMemberPatch patch = json.readValue("{\"hardLimitGB\":5,\"softLimitGB\":null}", PoolMemberPatch.class);
        assertEquals(new BigDecimal("5"), PoolMemberPatch.value(patch.hardLimitGB()));
        assertNull(PoolMemberPatch.value(patch.softLimitGB()));
        assertTrue(patch.softLimitGB().isNull());
        assertNull(json.readValue("{}", PoolMemberPatch.class).softLimitGB());
    }

    @Test
    void autoTopup_disabledShape_fullShape_andTheRequest() throws Exception {
        assertEquals("{\"partyId\":\"p1\",\"enabled\":false,\"@type\":\"AutoTopupPolicy\"}",
                json.writeValueAsString(AutoTopupPolicyView.disabled("p1")));
        assertEquals("{\"partyId\":\"p1\",\"enabled\":true,\"boostOfferingId\":\"po-b\",\"trigger\":\"thresholdPct\",\"triggerPct\":80.00,"
                + "\"maxBoostsPerCycle\":2,\"maxSpendPerCycle\":100.00,\"consentAt\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"AutoTopupPolicy\"}",
                json.writeValueAsString(new AutoTopupPolicyView("p1", true, "po-b", "thresholdPct", new BigDecimal("80.00"), 2,
                        new BigDecimal("100.00"), AT.toString(), "AutoTopupPolicy")));
        AutoTopupPolicyRequest on = json.readValue("{\"enabled\":true,\"consent\":true,\"boostOfferingId\":\"po-b\",\"maxBoostsPerCycle\":\"2\"}",
                AutoTopupPolicyRequest.class);
        assertTrue(on.enable());
        assertTrue(on.consented());
        assertEquals(2, on.maxBoostsPerCycle());
        assertFalse(on.hasMaxSpendPerCycle());
        AutoTopupPolicyRequest off = json.readValue("{\"enabled\":false,\"maxSpendPerCycle\":null}", AutoTopupPolicyRequest.class);
        assertFalse(off.enable());
        assertTrue(off.hasMaxSpendPerCycle());
        assertNull(off.maxSpendPerCycleValue());
    }

    @Test
    void pool_withAndWithoutMembers() throws Exception {
        PoolView owner = new PoolView("pool-1", "/tmf-api/usageManagement/v4/allowancePool/pool-1", "Snap pool", "p1", "snap data",
                new BigDecimal("20.000"), new BigDecimal("0.000"), new BigDecimal("20.000"), "GB", "active", "AllowancePool",
                List.of(new PoolMemberView("p1", new BigDecimal("0.000"), null, null, "active"),
                        new PoolMemberView("p2", BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("2"), "active")));
        assertEquals("{\"id\":\"pool-1\",\"href\":\"/tmf-api/usageManagement/v4/allowancePool/pool-1\",\"name\":\"Snap pool\","
                + "\"ownerPartyId\":\"p1\",\"usageType\":\"snap data\",\"poolGB\":20.000,\"consumedGB\":0.000,\"remainingGB\":20.000,"
                + "\"units\":\"GB\",\"status\":\"active\",\"@type\":\"AllowancePool\",\"member\":[{\"partyId\":\"p1\",\"consumedGB\":0.000,\"status\":\"active\"},"
                + "{\"partyId\":\"p2\",\"consumedGB\":0,\"softLimitGB\":1,\"hardLimitGB\":2,\"status\":\"active\"}]}",
                json.writeValueAsString(owner));
        PoolView member = new PoolView("pool-1", "/x", "Family data pool", "p1", null, new BigDecimal("10.000"),
                new BigDecimal("2.000"), new BigDecimal("8.000"), "GB", "active", "AllowancePool", null);
        assertEquals("{\"id\":\"pool-1\",\"href\":\"/x\",\"name\":\"Family data pool\",\"ownerPartyId\":\"p1\",\"poolGB\":10.000,"
                + "\"consumedGB\":2.000,\"remainingGB\":8.000,\"units\":\"GB\",\"status\":\"active\",\"@type\":\"AllowancePool\"}",
                json.writeValueAsString(member));
    }

    // ---- OCS seam and TMF654 ----

    @Test
    void ocsSubscriber_readsTheMockShape_andProjectsTheBucket() throws Exception {
        OcsSubscriber sub = json.readValue("{\"id\":\"sub-1\",\"tenantId\":\"genalpha\",\"partyId\":\"p1\",\"serviceId\":\"svc\","
                + "\"ratePlanId\":\"RG-DATA-10\",\"buckets\":[{\"id\":\"bkt-1\",\"name\":\"10 GB counter\",\"ratePlanId\":\"RG-DATA-10\","
                + "\"totalGB\":10,\"usedGB\":3.5,\"rollover\":false,\"extra\":1}],\"createdAt\":\"x\"}", OcsSubscriber.class);
        assertEquals(1, sub.bucketList().size());
        assertEquals(0.0, sub.bucketList().get(0).rolloverGB());
        assertTrue(sub.hasBucket("bkt-1"));
        PrepayBucketView view = new PrepayBucketView("bkt-1", "Bucket", "10 GB counter", "RG-DATA-10", "sub-1", "svc",
                new Amount(6.5, "GB"), new Amount(3.5, "GB"), new Amount(0.0, "GB"), false, List.of(RelatedPartyRef.customer("p1")));
        assertEquals("{\"id\":\"bkt-1\",\"@type\":\"Bucket\",\"name\":\"10 GB counter\",\"ratePlanId\":\"RG-DATA-10\",\"subscriberId\":\"sub-1\","
                + "\"serviceId\":\"svc\",\"remainingValue\":{\"amount\":6.5,\"units\":\"GB\"},\"usedValue\":{\"amount\":3.5,\"units\":\"GB\"},"
                + "\"rolloverValue\":{\"amount\":0.0,\"units\":\"GB\"},\"isRolloverEligible\":false,\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}",
                json.writeValueAsString(view));
    }

    // ---- wholesale, both faces ----

    @Test
    void wholesaleLedger_reratedRowsCarryTheReceipt_andTheEventUnwrapsTheRowFirst() throws Exception {
        WholesaleLedgerView row = new WholesaleLedgerView("l1", "2026-09-01", "Mobile data", new BigDecimal("33.500"), "GB",
                new BigDecimal("2.500000"), new BigDecimal("83.75"), "EUR", "host-1", "rated", null, null, "WholesaleUsageLedger");
        assertEquals("{\"id\":\"l1\",\"periodStart\":\"2026-09-01\",\"usageSpecName\":\"Mobile data\",\"totalUnits\":33.500,\"unit\":\"GB\","
                + "\"wholesaleRate\":2.500000,\"amount\":83.75,\"currency\":\"EUR\",\"hostPartyId\":\"host-1\",\"status\":\"rated\",\"@type\":\"WholesaleUsageLedger\"}",
                json.writeValueAsString(row));
        WholesaleLedgerView moved = new WholesaleLedgerView("l1", "2026-09-01", "Mobile data", new BigDecimal("40.000"), "GB",
                new BigDecimal("2.500000"), new BigDecimal("100.00"), "EUR", "host-1", "rated", 1, AT, "WholesaleUsageLedger");
        assertEquals("{\"id\":\"l1\",\"periodStart\":\"2026-09-01\",\"usageSpecName\":\"Mobile data\",\"totalUnits\":40.000,\"unit\":\"GB\","
                + "\"wholesaleRate\":2.500000,\"amount\":100.00,\"currency\":\"EUR\",\"hostPartyId\":\"host-1\",\"status\":\"rated\","
                + "\"rerateCount\":1,\"lastReratedAt\":\"2026-09-22T09:08:12.169178Z\",\"@type\":\"WholesaleUsageLedger\",\"previousAmount\":83.75,\"delta\":16.25}",
                json.writeValueAsString(new WholesaleRerated(moved, new BigDecimal("83.75"), new BigDecimal("16.25"))));
    }

    @Test
    void wholesaleSimulation_settlement_rateCard_imsi() throws Exception {
        WholesaleSimulation sim = new WholesaleSimulation("WholesaleNegotiationSimulation", "2026-09-01", "2026-09-30",
                List.of(new WholesaleSimulation.Line("Mobile data", new BigDecimal("33.500"), "GB", new BigDecimal("2.500000"),
                        new BigDecimal("0.5"), new BigDecimal("83.75"), new BigDecimal("16.75"), new BigDecimal("-67.00"))),
                new BigDecimal("83.75"), new BigDecimal("16.75"), new BigDecimal("-67.00"), null, List.of("a"));
        assertEquals("{\"@type\":\"WholesaleNegotiationSimulation\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\","
                + "\"line\":[{\"usageSpecName\":\"Mobile data\",\"units\":33.500,\"unit\":\"GB\",\"currentRate\":2.500000,\"proposedRate\":0.5,"
                + "\"currentCost\":83.75,\"proposedCost\":16.75,\"delta\":-67.00}],\"currentTotal\":83.75,\"proposedTotal\":16.75,\"delta\":-67.00,"
                + "\"assumptions\":[\"a\"]}", json.writeValueAsString(sim));
        WholesaleSettlement empty = new WholesaleSettlement("MobileWholesaleSettlement", "month", "2020-01-01", null, List.of(),
                new BigDecimal("0.00"), "EUR", true);
        assertEquals("{\"@type\":\"MobileWholesaleSettlement\",\"periodType\":\"month\",\"periodStart\":\"2020-01-01\",\"hostPartyId\":null,"
                + "\"line\":[],\"totalOwed\":0.00,\"currency\":\"EUR\",\"reconciled\":true}", json.writeValueAsString(empty));
        assertEquals("{\"usageSpecName\":\"Mobile data\",\"ratedUnits\":33.500,\"liveUnits\":34.000,\"unit\":\"GB\",\"wholesaleRate\":2.500000,"
                + "\"amount\":83.75,\"currency\":\"EUR\",\"reconciled\":false}",
                json.writeValueAsString(new WholesaleSettlement.Line("Mobile data", new BigDecimal("33.500"), new BigDecimal("34.000"), "GB",
                        new BigDecimal("2.500000"), new BigDecimal("83.75"), "EUR", false)));
        assertEquals("{\"id\":\"c1\",\"usageSpecName\":\"snap wholesale\",\"wholesaleRate\":1.250000,\"unit\":\"GB\",\"currency\":\"EUR\","
                + "\"hostPartyId\":null,\"hostName\":null,\"@type\":\"WholesaleRateCard\"}",
                json.writeValueAsString(new WholesaleRateCardView("c1", "snap wholesale", new BigDecimal("1.250000"), "GB", "EUR", null, null, "WholesaleRateCard")));
        assertEquals("{\"id\":\"i1\",\"hostPartyId\":\"host\",\"hostName\":null,\"prefix\":\"24299\",\"fromImsi\":\"242990000000000\","
                + "\"toImsi\":\"242990000000099\",\"capacity\":100,\"note\":null,\"@type\":\"ImsiRange\"}",
                json.writeValueAsString(new ImsiRangeView("i1", "host", null, "24299", "242990000000000", "242990000000099", 100, null, "ImsiRange")));
        SimulateWholesaleRequest req = json.readValue("{\"rateCard\":[{\"usageSpecName\":\"x\",\"wholesaleRate\":0.5}]}", SimulateWholesaleRequest.class);
        assertEquals(new BigDecimal("0.5"), req.rates().get(0).wholesaleRate());
        assertTrue(SimulateWholesaleRequest.EMPTY.rates().isEmpty());
    }

    @Test
    void providerFace_ledger_rerated_settlement_statement_rateCard() throws Exception {
        ProviderLedgerView row = new ProviderLedgerView("r1", "mvno-1", "Snap MVNO", "2020-01-01", "snap provider data",
                new BigDecimal("40.0000"), "GB", new BigDecimal("2.500000"), new BigDecimal("100.00"), "EUR", "ProviderUsageLedger");
        assertEquals("{\"id\":\"r1\",\"mvnoPartyId\":\"mvno-1\",\"mvnoName\":\"Snap MVNO\",\"periodStart\":\"2020-01-01\","
                + "\"usageSpecName\":\"snap provider data\",\"totalUnits\":40.0000,\"unit\":\"GB\",\"rate\":2.500000,\"amount\":100.00,"
                + "\"currency\":\"EUR\",\"@type\":\"ProviderUsageLedger\"}", json.writeValueAsString(row));
        ProviderUsageResult rerated = new ProviderUsageResult.Rerated(row, new BigDecimal("97.50"), new BigDecimal("2.50"), 1);
        assertTrue(json.writeValueAsString(rerated).endsWith("\"@type\":\"ProviderUsageLedger\",\"previousAmount\":97.50,\"delta\":2.50,\"rerateCount\":1}"));
        ProviderLine line = new ProviderLine("snap provider data", new BigDecimal("40.0000"), "GB", new BigDecimal("2.500000"),
                new BigDecimal("100.00"), "EUR");
        assertEquals("{\"@type\":\"MobileWholesaleProviderSettlement\",\"periodType\":\"month\",\"periodStart\":\"2020-01-01\","
                + "\"mvno\":[{\"mvnoPartyId\":\"mvno-1\",\"mvnoName\":\"Snap MVNO\",\"line\":[{\"usageSpecName\":\"snap provider data\","
                + "\"totalUnits\":40.0000,\"unit\":\"GB\",\"rate\":2.500000,\"amount\":100.00,\"currency\":\"EUR\"}],\"total\":100.00}],"
                + "\"totalRevenue\":100.00,\"currency\":\"EUR\"}",
                json.writeValueAsString(new ProviderSettlement("MobileWholesaleProviderSettlement", "month", "2020-01-01",
                        List.of(new ProviderSettlement.Mvno("mvno-1", "Snap MVNO", List.of(line), new BigDecimal("100.00"))),
                        new BigDecimal("100.00"), "EUR")));
        assertEquals("{\"@type\":\"MobileWholesaleStatement\",\"mvnoPartyId\":\"nobody\",\"mvnoName\":null,\"periodStart\":\"2020-01-01\","
                + "\"line\":[],\"totalOwed\":0.00,\"currency\":\"EUR\"}",
                json.writeValueAsString(new MvnoStatement("MobileWholesaleStatement", "nobody", null, "2020-01-01", List.of(),
                        new BigDecimal("0.00"), "EUR")));
        assertEquals("{\"id\":\"c1\",\"mvnoPartyId\":null,\"mvnoName\":null,\"usageSpecName\":\"snap provider data\",\"rate\":3.000000,"
                + "\"unit\":\"GB\",\"currency\":\"EUR\",\"@type\":\"ProviderRateCard\"}",
                json.writeValueAsString(new ProviderRateCardView("c1", null, null, "snap provider data", new BigDecimal("3.000000"), "GB", "EUR", "ProviderRateCard")));
    }

    @Test
    void rateUsageRequest_parsesDates() throws Exception {
        RateUsageRequest one = json.readValue("{\"relatedPartyId\":\"p1\",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}",
                RateUsageRequest.class);
        assertEquals(LocalDate.parse("2026-09-01"), one.periodStart());
        RateUsageRequest many = json.readValue("{\"relatedPartyIds\":[\"p1\",\"p2\"],\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}",
                RateUsageRequest.class);
        assertEquals(List.of("p1", "p2"), many.relatedPartyIds());
    }
}
