package com.bss.billing;

import com.bss.billing.dto.AppliedBillingRateView;
import com.bss.billing.dto.AttachmentRef;
import com.bss.billing.dto.BillFormatProfileRequest;
import com.bss.billing.dto.BillingRunResult;
import com.bss.billing.dto.ChannelConsentResult;
import com.bss.billing.dto.CollectionCaseView;
import com.bss.billing.dto.CreditNoteRequest;
import com.bss.billing.dto.CreditNoteView;
import com.bss.billing.dto.CreditedLine;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.dto.DirectDebitDtos;
import com.bss.billing.dto.DisputeChip;
import com.bss.billing.dto.DisputeView;
import com.bss.billing.dto.DunningPolicyRequest;
import com.bss.billing.dto.DunningPolicyView;
import com.bss.billing.dto.DunningStepReached;
import com.bss.billing.dto.EntityRef;
import com.bss.billing.dto.InstallmentPlanEvent;
import com.bss.billing.dto.InstallmentPlanView;
import com.bss.billing.dto.MigrationRehearsalDtos;
import com.bss.billing.dto.Money;
import com.bss.billing.dto.MoneyDto;
import com.bss.billing.dto.PaymentRef;
import com.bss.billing.dto.RelatedPartyRef;
import com.bss.billing.dto.RemittanceReceipt;
import com.bss.billing.dto.TimePeriod;
import com.bss.billing.service.CountryStatutoryPack;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the
 * same order, money with the scale the entity stores, absent keys absent,
 * null keys null where they were null. Pure Jackson, configured as Spring
 * Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-03T09:08:12.169178Z");

    @Test
    void money_keepsTheEntityScale() throws Exception {
        assertEquals("{\"unit\":\"EUR\",\"value\":0.00}", json.writeValueAsString(new Money("EUR", new BigDecimal("0.00"))));
        assertEquals("{\"unit\":\"EUR\",\"value\":297.58}", json.writeValueAsString(new Money("EUR", new BigDecimal("297.58"))));
        assertEquals("{\"unit\":\"\",\"value\":0}", json.writeValueAsString(new Money("", BigDecimal.ZERO)));
    }

    @Test
    void customerBill_writesTheTmf678ShapeInOrder() throws Exception {
        CustomerBillDto dto = new CustomerBillDto();
        dto.setId("b1");
        dto.setHref("/tmf-api/customerBillManagement/v4/customerBill/b1");
        dto.setBillNo("BILL-202608-B1");
        dto.setState("settled");
        dto.setBillingAccount(EntityRef.billingAccount("p1-account"));
        dto.setBillDocument(List.of(AttachmentRef.pdfOf("b1", "BILL-202608-B1", dto.getHref())));
        dto.setAmountDue(new MoneyDto("EUR", new BigDecimal("0.00")));
        dto.setDispute(new DisputeChip("d1", "open", "wrong"));
        dto.setBillingPeriod(TimePeriod.ofDates(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")));
        dto.setRelatedParty(List.of(RelatedPartyRef.individual("p1")));
        dto.setPayment(List.of(PaymentRef.of("pay-1")));
        dto.setBillDate(AT);
        dto.setLastUpdate(AT);
        assertEquals("{\"id\":\"b1\",\"href\":\"/tmf-api/customerBillManagement/v4/customerBill/b1\","
                + "\"billNo\":\"BILL-202608-B1\",\"state\":\"settled\","
                + "\"billingAccount\":{\"id\":\"p1-account\",\"@referredType\":\"BillingAccount\",\"@type\":\"BillingAccountRef\"},"
                + "\"billDocument\":[{\"id\":\"b1-document\",\"name\":\"Bill BILL-202608-B1\",\"mimeType\":\"application/pdf\","
                + "\"@type\":\"AttachmentRefOrValue\",\"href\":\"/tmf-api/customerBillManagement/v4/customerBill/b1/document.pdf\","
                + "\"url\":\"/tmf-api/customerBillManagement/v4/customerBill/b1/document.pdf\"}],"
                + "\"amountDue\":{\"unit\":\"EUR\",\"value\":0.00},"
                + "\"dispute\":{\"id\":\"d1\",\"status\":\"open\",\"reason\":\"wrong\"},"
                + "\"billingPeriod\":{\"startDateTime\":\"2026-08-01\",\"endDateTime\":\"2026-08-31\"},"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                + "\"payment\":[{\"id\":\"pay-1\",\"@referredType\":\"Payment\"}],"
                + "\"billDate\":\"2026-08-03T09:08:12.169178Z\",\"lastUpdate\":\"2026-08-03T09:08:12.169178Z\","
                + "\"@type\":\"CustomerBill\"}", json.writeValueAsString(dto));
    }

    @Test
    void customerBill_patchParsesThePaymentReference() throws Exception {
        CustomerBillDto patch = json.readValue(
                "{\"state\":\"settled\",\"payment\":[{\"id\":\"pay-9\",\"@referredType\":\"Payment\",\"note\":\"card\"}],\"unknown\":1}",
                CustomerBillDto.class);
        assertEquals("pay-9", patch.paymentId());
        assertEquals("card", patch.getPayment().get(0).extensions().get("note"));
        // stored and read back, the reference is what the client sent
        List<PaymentRef> stored = json.readValue(json.writeValueAsString(patch.getPayment()),
                new TypeReference<List<PaymentRef>>() { });
        assertEquals("{\"id\":\"pay-9\",\"@referredType\":\"Payment\",\"note\":\"card\"}",
                json.writeValueAsString(stored.get(0)));
    }

    @Test
    void appliedRate_leavesOffWhatTheMapLeftOff() throws Exception {
        AppliedBillingRateView billed = new AppliedBillingRateView("r1", "/x/r1", "Fiber",
                "AppliedCustomerBillingRate", "recurringCharge", new Money("EUR", new BigDecimal("25.00")),
                null, true, EntityRef.of("p1"), EntityRef.of("b1"), AT.toString());
        assertEquals("{\"id\":\"r1\",\"href\":\"/x/r1\",\"name\":\"Fiber\",\"@type\":\"AppliedCustomerBillingRate\","
                + "\"type\":\"recurringCharge\",\"taxExcludedAmount\":{\"unit\":\"EUR\",\"value\":25.00},\"isBilled\":true,"
                + "\"forParty\":{\"id\":\"p1\"},\"bill\":{\"id\":\"b1\"},\"date\":\"2026-08-03T09:08:12.169178Z\"}",
                json.writeValueAsString(billed));
        AppliedBillingRateView standalone = new AppliedBillingRateView("r2", "/x/r2", "Fee",
                "AppliedCustomerBillingRate", "reconnectionFee", new Money("EUR", new BigDecimal("25")),
                List.of(new AppliedBillingRateView.AppliedTax("VAT", new BigDecimal("25.000"))), false,
                null, null, AT.toString());
        assertEquals("{\"id\":\"r2\",\"href\":\"/x/r2\",\"name\":\"Fee\",\"@type\":\"AppliedCustomerBillingRate\","
                + "\"type\":\"reconnectionFee\",\"taxExcludedAmount\":{\"unit\":\"EUR\",\"value\":25},"
                + "\"appliedTax\":[{\"taxCategory\":\"VAT\",\"taxRate\":25.000}],\"isBilled\":false,"
                + "\"date\":\"2026-08-03T09:08:12.169178Z\"}", json.writeValueAsString(standalone));
    }

    @Test
    void installmentPlan_writesNullNextAmountButNoNextDueAtWhenDone() throws Exception {
        InstallmentPlanView done = new InstallmentPlanView("b1", 3, 3, new BigDecimal("10.00"),
                new BigDecimal("10.01"), null, "EUR", "completed", null, "InstallmentPlan");
        assertEquals("{\"billId\":\"b1\",\"installments\":3,\"paidCount\":3,\"amountPer\":10.00,\"lastAmount\":10.01,"
                + "\"nextAmount\":null,\"currency\":\"EUR\",\"status\":\"completed\",\"@type\":\"InstallmentPlan\"}",
                json.writeValueAsString(done));
        InstallmentPlanView open = new InstallmentPlanView("b1", 3, 1, new BigDecimal("10.00"),
                new BigDecimal("10.01"), new BigDecimal("10.00"), "EUR", "active", AT.toString(), "InstallmentPlan");
        assertEquals("{\"billId\":\"b1\",\"installments\":3,\"paidCount\":1,\"amountPer\":10.00,\"lastAmount\":10.01,"
                + "\"nextAmount\":10.00,\"currency\":\"EUR\",\"status\":\"active\",\"nextDueAt\":\"2026-08-03T09:08:12.169178Z\","
                + "\"@type\":\"InstallmentPlan\",\"billNo\":\"BILL-1\",\"paidAmount\":10.00,"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}]}",
                json.writeValueAsString(new InstallmentPlanEvent(open, "BILL-1", new BigDecimal("10.00"),
                        List.of(RelatedPartyRef.customer("p1")))));
    }

    @Test
    void collectionCase_holdsAreAnObjectEvenWhenEmpty() throws Exception {
        CollectionCaseView quiet = new CollectionCaseView("c1", "a1", "suspended",
                new Money("EUR", new BigDecimal("297.58")), AT.toString(), 4, 1, AT.toString(),
                new CollectionCaseView.Holds(null, null, null), List.of("s1", "s2"), null, null, null,
                List.of(RelatedPartyRef.customer("a1")), "CollectionCase");
        assertEquals("{\"id\":\"c1\",\"accountId\":\"a1\",\"state\":\"suspended\","
                + "\"overdueBalance\":{\"unit\":\"EUR\",\"value\":297.58},\"oldestDueAt\":\"2026-08-03T09:08:12.169178Z\","
                + "\"stepIndex\":4,\"feeCount\":1,\"warnedAt\":\"2026-08-03T09:08:12.169178Z\",\"holds\":{},"
                + "\"enforcedServices\":[\"s1\",\"s2\"],\"relatedParty\":[{\"id\":\"a1\",\"role\":\"customer\"}],"
                + "\"@type\":\"CollectionCase\"}", json.writeValueAsString(quiet));
        CollectionCaseView held = new CollectionCaseView("c1", "a1", "current",
                new Money("", BigDecimal.ZERO), null, 0, 0, null,
                new CollectionCaseView.Holds(new CollectionCaseView.PromiseHold(new BigDecimal("100.00"), AT.toString()),
                        new CollectionCaseView.DisputeHold(new BigDecimal("5.50")), true),
                List.of(), AT.toString(), AT.toString(), "gone", List.of(RelatedPartyRef.customer("a1")), "CollectionCase");
        assertEquals("{\"id\":\"c1\",\"accountId\":\"a1\",\"state\":\"current\",\"overdueBalance\":{\"unit\":\"\",\"value\":0},"
                + "\"stepIndex\":0,\"feeCount\":0,\"holds\":{\"promiseToPay\":{\"amount\":100.00,\"dueAt\":\"2026-08-03T09:08:12.169178Z\"},"
                + "\"dispute\":{\"amount\":5.50},\"hardship\":true},\"enforcedServices\":[],"
                + "\"curedAt\":\"2026-08-03T09:08:12.169178Z\",\"writtenOffAt\":\"2026-08-03T09:08:12.169178Z\",\"writeOffReason\":\"gone\","
                + "\"relatedParty\":[{\"id\":\"a1\",\"role\":\"customer\"}],\"@type\":\"CollectionCase\"}",
                json.writeValueAsString(held));
        // the step event: the case's keys, then the rung and the bill
        String event = json.writeValueAsString(new DunningStepReached(quiet,
                new DunningStepReached.Step("warn", 16, "dunning-warning", null, AT.toString()), "BILL-1"));
        assertTrue(event.startsWith("{\"id\":\"c1\","), event);
        assertTrue(event.endsWith("\"@type\":\"CollectionCase\",\"step\":{\"action\":\"warn\",\"offsetDays\":16,"
                + "\"templateId\":\"dunning-warning\",\"enforceableAt\":\"2026-08-03T09:08:12.169178Z\"},\"billNo\":\"BILL-1\"}"), event);
    }

    @Test
    void dunningPolicy_carriesTheLadderAsWrittenAndThePackBesideIt() throws Exception {
        JsonNode steps = json.readTree("[{\"offsetDays\":14,\"action\":\"remind\",\"feeAmount\":38.0},{\"offsetDays\":16,\"action\":\"warn\"}]");
        DunningPolicyView view = new DunningPolicyView("dp1", "std", "NO", 14, new BigDecimal("250.00"), null, steps,
                new BigDecimal("25.00"), new BigDecimal("500.00"), 2, 90, 14, new BigDecimal("0.00"), true,
                CountryStatutoryPack.of("NO"), "DunningPolicy");
        assertEquals("{\"id\":\"dp1\",\"name\":\"std\",\"country\":\"NO\",\"paymentTermDays\":14,\"entryThreshold\":250.00,"
                + "\"steps\":[{\"offsetDays\":14,\"action\":\"remind\",\"feeAmount\":38.0},{\"offsetDays\":16,\"action\":\"warn\"}],"
                + "\"reconnectionFee\":25.00,\"writeOffThreshold\":500.00,\"promiseMaxPerPeriod\":2,\"promisePeriodDays\":90,"
                + "\"promiseMaxDays\":14,\"autoRefundThreshold\":0.00,\"active\":true,"
                + "\"statutory\":{\"country\":\"NO\",\"reminderFeeGateDays\":14,\"reminderFeeCap\":38.00,\"maxFeeBearingReminders\":2,"
                + "\"enforcementNoticeDays\":30,\"minActionableAmount\":250.00,\"mrcStopsWhileSuspended\":true,\"emergencyAlwaysReachable\":true},"
                + "\"@type\":\"DunningPolicy\"}", json.writeValueAsString(view));
        // a country without a pack: no cap key, the permissive numbers
        assertEquals("{\"country\":\"??\",\"reminderFeeGateDays\":0,\"maxFeeBearingReminders\":2147483647,\"enforcementNoticeDays\":0,"
                + "\"minActionableAmount\":0,\"mrcStopsWhileSuspended\":false,\"emergencyAlwaysReachable\":true}",
                json.writeValueAsString(CountryStatutoryPack.of("GY")));
        // the request: numbers as strings coerce, the ladder stays a tree, unknown keys are ignored
        DunningPolicyRequest req = json.readValue("{\"name\":\"std\",\"paymentTermDays\":\"14\",\"entryThreshold\":\"250.00\","
                + "\"steps\":[{\"offsetDays\":14,\"action\":\"remind\"}],\"active\":\"true\",\"tenantId\":\"evil\"}",
                DunningPolicyRequest.class);
        assertEquals(14, req.paymentTermDays());
        assertEquals(new BigDecimal("250.00"), req.entryThreshold());
        assertTrue(req.active());
        assertTrue(req.hasSteps());
        assertEquals("[{\"offsetDays\":14,\"action\":\"remind\"}]", json.writeValueAsString(req.steps()));
    }

    @Test
    void creditNote_linesReadBackAsDecimals() throws Exception {
        List<CreditedLine> lines = json.readValue("[{\"id\":\"r1\",\"amount\":0.30,\"name\":\"Home\"}]",
                new TypeReference<List<CreditedLine>>() { });
        assertEquals(new BigDecimal("0.30"), lines.get(0).amount());
        CreditNoteView view = new CreditNoteView("cn1", "/x/cn1", "CN-000001", "b1", "BILL-1",
                new Money("EUR", new BigDecimal("0.30")), "line correction", "reduced", null, null, lines,
                List.of(RelatedPartyRef.individual("p1")), AT, "CreditNote");
        assertEquals("{\"id\":\"cn1\",\"href\":\"/x/cn1\",\"creditNoteNo\":\"CN-000001\",\"billId\":\"b1\",\"billNo\":\"BILL-1\","
                + "\"amount\":{\"unit\":\"EUR\",\"value\":0.30},\"reason\":\"line correction\",\"settlement\":\"reduced\","
                + "\"creditedLines\":[{\"id\":\"r1\",\"name\":\"Home\",\"amount\":0.30}],"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                + "\"issuedAt\":\"2026-08-03T09:08:12.169178Z\",\"@type\":\"CreditNote\"}", json.writeValueAsString(view));
        CreditNoteView refunded = new CreditNoteView("cn2", "/x/cn2", "CN-000002", "b1", "BILL-1",
                new Money("EUR", new BigDecimal("1.00")), "full", "refunded", "pay-1", "d1", List.of(), null, AT, "CreditNote");
        assertEquals("{\"id\":\"cn2\",\"href\":\"/x/cn2\",\"creditNoteNo\":\"CN-000002\",\"billId\":\"b1\",\"billNo\":\"BILL-1\","
                + "\"amount\":{\"unit\":\"EUR\",\"value\":1.00},\"reason\":\"full\",\"settlement\":\"refunded\",\"refundRef\":\"pay-1\","
                + "\"disputeId\":\"d1\",\"issuedAt\":\"2026-08-03T09:08:12.169178Z\",\"@type\":\"CreditNote\"}",
                json.writeValueAsString(refunded));
        CreditNoteRequest req = json.readValue("{\"reason\":\"x\",\"lines\":[{\"id\":\"r1\",\"amount\":\"2.50\"}]}", CreditNoteRequest.class);
        assertEquals(new BigDecimal("2.50"), req.lines().get(0).amount());
        assertNull(req.amount());
    }

    @Test
    void dispute_resolvedAppendsTheBillStateAfterTheType() throws Exception {
        DisputeView open = new DisputeView("d1", "b1", "BILL-1", "wrong", "open", null, null, AT.toString(), "p1",
                List.of(RelatedPartyRef.customer("p1")), "BillDispute", null);
        assertEquals("{\"id\":\"d1\",\"billId\":\"b1\",\"billNo\":\"BILL-1\",\"reason\":\"wrong\",\"status\":\"open\","
                + "\"createdAt\":\"2026-08-03T09:08:12.169178Z\",\"partyId\":\"p1\",\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\"}],"
                + "\"@type\":\"BillDispute\"}", json.writeValueAsString(open));
        assertTrue(json.writeValueAsString(open.resolved("settled")).endsWith("\"@type\":\"BillDispute\",\"billState\":\"settled\"}"));
    }

    @Test
    void formatProfile_absentLeavesAloneAndNullClears() throws Exception {
        BillFormatProfileRequest absent = json.readValue("{\"name\":\"EHF\"}", BillFormatProfileRequest.class);
        assertFalse(BillFormatProfileRequest.given(absent.customizationId()));
        BillFormatProfileRequest cleared = json.readValue("{\"customizationId\":null}", BillFormatProfileRequest.class);
        assertTrue(BillFormatProfileRequest.given(cleared.customizationId()));
        assertNull(BillFormatProfileRequest.textOf(cleared.customizationId()));
        BillFormatProfileRequest set = json.readValue("{\"customizationId\":\"urn:x\"}", BillFormatProfileRequest.class);
        assertEquals("urn:x", BillFormatProfileRequest.textOf(set.customizationId()));
    }

    @Test
    void receipts_writeTheirKeysInOrder() throws Exception {
        assertEquals("{\"billsCreated\":1,\"customersSkipped\":2,\"accountsFailed\":0,\"runId\":\"r\","
                + "\"billingPeriod\":{\"startDateTime\":\"2026-09-01\",\"endDateTime\":\"2026-09-30\"}}",
                json.writeValueAsString(new BillingRunResult.Receipt(1, 2, 0, "r",
                        TimePeriod.ofDates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")))));
        assertEquals("{\"busy\":true,\"note\":\"n\"}", json.writeValueAsString(new BillingRunResult.Busy(true, "n")));
        assertEquals("{\"partyId\":\"p\",\"channel\":\"mailbox\",\"consented\":false}",
                json.writeValueAsString(new ChannelConsentResult.Withdrawn("p", "mailbox", false)));
        assertEquals("{\"batchRef\":\"B\",\"entries\":2,\"applied\":1,\"unapplied\":1,\"source\":\"directDebit\","
                + "\"settledClaims\":[],\"@type\":\"SettlementFile\"}",
                json.writeValueAsString(new DirectDebitDtos.SettlementFileReceipt(
                        new RemittanceReceipt("B", 2, 1, 1), "directDebit", List.of(), "SettlementFile")));
        MigrationRehearsalDtos.Report report = new MigrationRehearsalDtos.Report("MigrationRehearsal", 1, 0, 0, 1, false,
                new MigrationRehearsalDtos.Exceptions(List.of(),
                        List.of(new MigrationRehearsalDtos.Missing("LEG-1", "Old", new BigDecimal("50"), "no offering"))),
                List.of("a"), null, null);
        assertEquals("{\"@type\":\"MigrationRehearsal\",\"rows\":1,\"matched\":0,\"priceDiffers\":0,\"offeringMissing\":1,"
                + "\"readyToCutOver\":false,\"exceptions\":{\"priceDiffers\":[],\"offeringMissing\":[{\"externalRef\":\"LEG-1\","
                + "\"offeringName\":\"Old\",\"expectedMonthly\":50,\"reason\":\"no offering\"}]},\"assumptions\":[\"a\"]}",
                json.writeValueAsString(report));
        assertTrue(json.writeValueAsString(report.saved("id1", "n")).endsWith("\"assumptions\":[\"a\"],\"id\":\"id1\",\"name\":\"n\"}"));
        // a priced row with no legacy expectation still writes its null delta, as the map did
        assertEquals("{\"externalRef\":\"L\",\"offeringName\":\"O\",\"expectedMonthly\":null,\"currentMonthly\":10,\"delta\":null}",
                json.writeValueAsString(new MigrationRehearsalDtos.Priced("L", "O", null, BigDecimal.TEN, null)));
    }
}
