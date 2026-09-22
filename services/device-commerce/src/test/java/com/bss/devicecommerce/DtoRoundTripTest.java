package com.bss.devicecommerce;

import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.DeviceAgreementView;
import com.bss.devicecommerce.dto.DeviceChargeRequest;
import com.bss.devicecommerce.dto.DeviceFlagView;
import com.bss.devicecommerce.dto.DeviceInstallmentUnwind;
import com.bss.devicecommerce.dto.DeviceRef;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.dto.GradingEventView;
import com.bss.devicecommerce.dto.RelatedPartyRef;
import com.bss.devicecommerce.dto.SettleReceipt;
import com.bss.devicecommerce.dto.SwapReceipt;
import com.bss.devicecommerce.dto.TradeInQuoteRequest;
import com.bss.devicecommerce.dto.TradeInResidualView;
import com.bss.devicecommerce.dto.TradeInValuationView;
import com.bss.devicecommerce.dto.UpgradeEligibility;
import com.bss.devicecommerce.dto.UpgradeRule;
import com.bss.devicecommerce.dto.WithdrawalCaseView;
import com.bss.devicecommerce.dto.WithdrawalReceipt;
import com.bss.devicecommerce.dto.WithdrawalRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final String HREF = "/tmf-api/deviceCommerce/v1/deviceAgreement/a1";
    private static final BigDecimal M720 = new BigDecimal("720.00");
    private static final BigDecimal M30 = new BigDecimal("30.00");

    private static DeviceAgreementView fullAgreement() {
        return new DeviceAgreementView("a1", HREF, "OPERATOR_BOOK", "active", "sub-1", "order-1",
                DeviceRef.logicalResource("phone-x", "351000000000001", "SN-1"), M720, 24, M30, M720, "EUR", 12,
                new BigDecimal("50.0"), new BigDecimal("360.00"), null, null, "operator",
                UpgradeRule.of("paidSharePct", new BigDecimal("50.00")), new BigDecimal("100.00"),
                new BigDecimal("240.00"), new BigDecimal("9.90"), "pay-1", null, null, null,
                List.of(RelatedPartyRef.customer("party-1")), DeviceAgreementView.TYPE);
    }

    private static DeviceAgreementView bareAgreement() {
        return new DeviceAgreementView("a2", HREF, "OPERATOR_BOOK", "settled", null, null, null,
                new BigDecimal("100.00"), 3, new BigDecimal("33.33"), new BigDecimal("100.00"), "EUR", 0,
                new BigDecimal("0.0"), new BigDecimal("100.00"), null, null, "operator", null, null,
                new BigDecimal("10.00"), null, null, null, null, null,
                List.of(RelatedPartyRef.customer("party-1")), DeviceAgreementView.TYPE);
    }

    @Test
    void deviceAgreement_writesEveryKeyInOrder_andLeavesAbsentOnesOff() throws Exception {
        assertEquals("{\"id\":\"a1\",\"href\":\"" + HREF + "\",\"financingModel\":\"OPERATOR_BOOK\","
                + "\"status\":\"active\",\"subscriptionRef\":\"sub-1\",\"orderRef\":\"order-1\","
                + "\"device\":{\"id\":\"phone-x\",\"imei\":\"351000000000001\",\"serialNumber\":\"SN-1\","
                + "\"@referredType\":\"LogicalResource\"},\"principal\":720.00,\"termMonths\":24,"
                + "\"monthlyAmount\":30.00,\"totalCostOfOwnership\":720.00,\"currency\":\"EUR\","
                + "\"installmentsPaid\":12,\"paidSharePct\":50.0,\"remainingPrincipal\":360.00,"
                + "\"titleHolder\":\"operator\",\"upgradeRule\":{\"paidSharePct\":50.00},"
                + "\"residualValue\":100.00,\"subsidyAmount\":240.00,\"shippingCost\":9.90,"
                + "\"paymentRef\":\"pay-1\",\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],"
                + "\"@type\":\"DeviceAgreement\"}", json.writeValueAsString(fullAgreement()));
        assertEquals("{\"id\":\"a2\",\"href\":\"" + HREF + "\",\"financingModel\":\"OPERATOR_BOOK\","
                + "\"status\":\"settled\",\"principal\":100.00,\"termMonths\":3,\"monthlyAmount\":33.33,"
                + "\"totalCostOfOwnership\":100.00,\"currency\":\"EUR\",\"installmentsPaid\":0,"
                + "\"paidSharePct\":0.0,\"remainingPrincipal\":100.00,\"titleHolder\":\"operator\","
                + "\"subsidyAmount\":10.00,\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],"
                + "\"@type\":\"DeviceAgreement\"}", json.writeValueAsString(bareAgreement()));
        // device without identifiers, bank facts, dates as ISO strings, the month rule
        DeviceAgreementView bank = new DeviceAgreementView("a3", HREF, "THIRD_PARTY_LOAN", "active", null, null,
                DeviceRef.logicalResource("phone-x", null, null), new BigDecimal("600.00"), 12,
                new BigDecimal("50.00"), new BigDecimal("600.00"), "EUR", 0, new BigDecimal("0.0"),
                new BigDecimal("600.00"), "mock-bank", "MB-ABCDEF01", "financier",
                UpgradeRule.of("month", new BigDecimal("6.00")), null, null, null, null,
                "2026-09-22T10:11:12.123456Z", "2026-09-23T10:11:12.123456Z", new BigDecimal("-50.00"),
                List.of(RelatedPartyRef.customer("party-1")), DeviceAgreementView.TYPE);
        assertEquals("{\"id\":\"a3\",\"href\":\"" + HREF + "\",\"financingModel\":\"THIRD_PARTY_LOAN\","
                + "\"status\":\"active\",\"device\":{\"id\":\"phone-x\",\"@referredType\":\"LogicalResource\"},"
                + "\"principal\":600.00,\"termMonths\":12,\"monthlyAmount\":50.00,\"totalCostOfOwnership\":600.00,"
                + "\"currency\":\"EUR\",\"installmentsPaid\":0,\"paidSharePct\":0.0,\"remainingPrincipal\":600.00,"
                + "\"financierRef\":\"mock-bank\",\"externalAgreementNo\":\"MB-ABCDEF01\",\"titleHolder\":\"financier\","
                + "\"upgradeRule\":{\"month\":6.00},\"payoutReceivedAt\":\"2026-09-22T10:11:12.123456Z\","
                + "\"deliveredAt\":\"2026-09-23T10:11:12.123456Z\",\"tradeInDelta\":-50.00,"
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],\"@type\":\"DeviceAgreement\"}",
                json.writeValueAsString(bank));
    }

    @Test
    void deviceAgreement_roundTrips() throws Exception {
        DeviceAgreementView view = fullAgreement();
        assertEquals(view, json.readValue(json.writeValueAsString(view), DeviceAgreementView.class));
    }

    @Test
    void settleAndSwapReceipts_appendTheirFactsAfterTheAgreement_andReplaysCarryNone() throws Exception {
        String agreement = json.writeValueAsString(bareAgreement());
        String settled = json.writeValueAsString(new SettleReceipt(bareAgreement(), new BigDecimal("25.00"),
                new BigDecimal("10.00")));
        assertEquals(agreement.substring(0, agreement.length() - 1) + ",\"etfAmount\":25.00,\"remainingSubsidy\":10.00}",
                settled);
        assertEquals(agreement, json.writeValueAsString(SettleReceipt.unchanged(bareAgreement())));

        FinancingSettlement writeOff = FinancingSettlement.writeOff("OPERATOR_BOOK", new BigDecimal("360.00"),
                new BigDecimal("300.00"), new BigDecimal("60.00"), BigDecimal.ZERO);
        String swapped = json.writeValueAsString(new SwapReceipt(bareAgreement(), writeOff, "v1", BigDecimal.ZERO));
        assertEquals(agreement.substring(0, agreement.length() - 1)
                + ",\"settlement\":{\"financingModel\":\"OPERATOR_BOOK\",\"remainingPrincipal\":360.00,"
                + "\"tradeInValue\":300.00,\"writeOff\":60.00,\"customerCredit\":0},"
                + "\"tradeInValuationId\":\"v1\",\"remainingSubsidy\":0}", swapped);
        assertEquals(agreement, json.writeValueAsString(SwapReceipt.unchanged(bareAgreement())));
    }

    @Test
    void financingSettlement_eachModelWritesOnlyItsOwnFacts() throws Exception {
        assertEquals("{\"financingModel\":\"THIRD_PARTY_LOAN\",\"settlementAmount\":349.00,\"tradeInValue\":300.00,"
                + "\"shortfall\":49.00,\"customerCredit\":0,\"externalAgreementNo\":\"MB-1\","
                + "\"note\":\"mock bank confirmed early settlement\"}",
                json.writeValueAsString(FinancingSettlement.bank("THIRD_PARTY_LOAN", new BigDecimal("349.00"),
                        new BigDecimal("300.00"), new BigDecimal("49.00"), BigDecimal.ZERO, "MB-1",
                        "mock bank confirmed early settlement")));
        String bnpl = json.writeValueAsString(FinancingSettlement.delegated("BNPL", "klarna", "settled",
                new BigDecimal("300.00"), "customer settles the provider; trade-in value credits the new purchase"));
        assertEquals("{\"financingModel\":\"BNPL\",\"settlementDelegated\":true,\"provider\":\"klarna\","
                + "\"providerSettlementStatus\":\"settled\",\"tradeInValue\":300.00,"
                + "\"note\":\"customer settles the provider; trade-in value credits the new purchase\"}", bnpl);
        assertTrue(!bnpl.contains("writeOff"));
    }

    @Test
    void financingQuote_andEarlySettlementQuote_keepEachDriversKeys() throws Exception {
        assertEquals("{\"financingModel\":\"OPERATOR_BOOK\",\"monthlyAmount\":30.00,\"totalCostOfOwnership\":720,"
                + "\"titleHolder\":\"operator\",\"note\":\"0% instalments on the operator's own book\"}",
                json.writeValueAsString(FinancingQuote.of("OPERATOR_BOOK", M30, new BigDecimal("720"), "operator",
                        "0% instalments on the operator's own book")));
        assertEquals("{\"financingModel\":\"THIRD_PARTY_LOAN\",\"monthlyAmount\":60.83,\"totalCostOfOwnership\":729.9,"
                + "\"titleHolder\":\"financier\",\"earlySettlementFee\":49,\"note\":\"n\"}",
                json.writeValueAsString(new FinancingQuote("THIRD_PARTY_LOAN", new BigDecimal("60.83"),
                        new BigDecimal("729.9"), "financier", new BigDecimal("49"), "n")));

        assertEquals("{\"financingModel\":\"OPERATOR_BOOK\",\"amount\":720.00,\"fee\":0,\"note\":\"n\","
                + "\"agreementId\":\"a1\",\"currency\":\"EUR\"}",
                json.writeValueAsString(EarlySettlementQuote.operatorBook("OPERATOR_BOOK", M720, BigDecimal.ZERO, "n")
                        .forAgreement("a1", "EUR")));
        assertEquals("{\"financingModel\":\"THIRD_PARTY_LOAN\",\"amount\":649.00,\"remainingPrincipal\":600.00,"
                + "\"fee\":49,\"financierRef\":\"mock-bank\",\"externalAgreementNo\":\"MB-1\","
                + "\"agreementId\":\"a1\",\"currency\":\"EUR\"}",
                json.writeValueAsString(EarlySettlementQuote.bank("THIRD_PARTY_LOAN", new BigDecimal("649.00"),
                        new BigDecimal("600.00"), new BigDecimal("49"), "mock-bank", "MB-1").forAgreement("a1", "EUR")));
        assertEquals("{\"financingModel\":\"BNPL\",\"amount\":500.00,\"settlementDelegated\":true,"
                + "\"provider\":\"klarna\",\"note\":\"n\",\"agreementId\":\"a1\",\"currency\":\"EUR\"}",
                json.writeValueAsString(EarlySettlementQuote.delegated("BNPL", new BigDecimal("500.00"), "klarna", "n")
                        .forAgreement("a1", "EUR")));
    }

    @Test
    void upgradeRule_oneKeyBothWays() throws Exception {
        assertEquals("paidSharePct", json.readValue("{\"paidSharePct\":50}", UpgradeRule.class).type());
        assertEquals("month", json.readValue("{\"month\":6}", UpgradeRule.class).type());
        assertNull(json.readValue("{\"other\":1}", UpgradeRule.class).type());
        assertEquals("{\"month\":6.00}", json.writeValueAsString(UpgradeRule.of("month", new BigDecimal("6.00"))));
        assertNull(UpgradeRule.of(null, null));
        assertEquals("{\"agreementId\":\"a1\",\"paidSharePct\":50.0,\"installmentsPaid\":12,\"eligible\":true,"
                + "\"reason\":\"paid share 50.0% vs required 50.00%\"}",
                json.writeValueAsString(new UpgradeEligibility("a1", new BigDecimal("50.0"), 12, true,
                        "paid share 50.0% vs required 50.00%")));
    }

    @Test
    void tradeInValuation_noteOnAQuote_historyOnARead_answersEchoedAsWritten() throws Exception {
        TradeInValuationView v = new TradeInValuationView("v1", "/tmf-api/deviceCommerce/v1/tradeInValuation/v1",
                "351000000000001", "phone-x", "quoted", new BigDecimal("300.00"), null, null, "EUR",
                "2026-10-22T10:11:12.123456Z", "shop", json.readTree("{\"ageMonths\":12,\"screenCracked\":true}"),
                null, null, null, List.of(RelatedPartyRef.customer("party-1")), TradeInValuationView.TYPE, null, null);
        String base = "{\"id\":\"v1\",\"href\":\"/tmf-api/deviceCommerce/v1/tradeInValuation/v1\","
                + "\"imei\":\"351000000000001\",\"deviceRef\":\"phone-x\",\"status\":\"quoted\","
                + "\"estimatedValue\":300.00,\"currency\":\"EUR\",\"offerExpiry\":\"2026-10-22T10:11:12.123456Z\","
                + "\"channel\":\"shop\",\"conditionAnswers\":{\"ageMonths\":12,\"screenCracked\":true},"
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],\"@type\":\"TradeInValuation\"";
        assertEquals(base + "}", json.writeValueAsString(v));
        assertEquals(base + ",\"note\":\"base 300.00 at age 12m\"}",
                json.writeValueAsString(v.withNote("base 300.00 at age 12m")));
        assertEquals(base + "}", json.writeValueAsString(v.withGrading(List.of())));
        assertEquals(base + ",\"gradingEvent\":[{\"id\":\"g1\",\"partnerRef\":\"in-house\",\"finalValue\":320.00,"
                + "\"delta\":20.00,\"createdAt\":\"2026-09-22T10:11:12.123456Z\"}]}",
                json.writeValueAsString(v.withGrading(List.of(new GradingEventView("g1", "in-house", null,
                        new BigDecimal("320.00"), new BigDecimal("20.00"), null, "2026-09-22T10:11:12.123456Z")))));
        assertEquals(v, json.readValue(json.writeValueAsString(v), TradeInValuationView.class));
        // a blacklisted quote: estimate ZERO stays 0, empty answers stay {}
        TradeInValuationView zero = new TradeInValuationView("v2", "h", "1", "phone-x", "quoted", BigDecimal.ZERO,
                null, null, "EUR", "t", "shop", json.readTree("{}"), null, null, null, null,
                TradeInValuationView.TYPE, null, null);
        assertEquals("{\"id\":\"v2\",\"href\":\"h\",\"imei\":\"1\",\"deviceRef\":\"phone-x\",\"status\":\"quoted\","
                + "\"estimatedValue\":0,\"currency\":\"EUR\",\"offerExpiry\":\"t\",\"channel\":\"shop\","
                + "\"conditionAnswers\":{},\"@type\":\"TradeInValuation\"}", json.writeValueAsString(zero));
    }

    @Test
    void residualAndFlag_rows() throws Exception {
        assertEquals("{\"id\":\"r1\",\"deviceRef\":\"phone-x\",\"ageMonths\":12,\"baseValue\":300.00,"
                + "\"currency\":\"EUR\",\"@type\":\"TradeInResidual\"}",
                json.writeValueAsString(new TradeInResidualView("r1", "phone-x", 12, new BigDecimal("300.00"), "EUR",
                        TradeInResidualView.TYPE)));
        assertEquals("{\"id\":\"f1\",\"imei\":\"1\",\"flag\":\"blacklisted\",\"reason\":\"stolen\","
                + "\"sourceRef\":\"manual\",\"createdAt\":\"t\",\"@type\":\"DeviceFlag\"}",
                json.writeValueAsString(new DeviceFlagView("f1", "1", "blacklisted", "stolen", "manual", "t",
                        DeviceFlagView.TYPE)));
    }

    @Test
    void withdrawalReceipt_writesTheAgreementFactsWhole_nullSubsidyIncluded_andNothingOnAReplay() throws Exception {
        WithdrawalCaseView w = new WithdrawalCaseView("w1", "/tmf-api/deviceCommerce/v1/withdrawalCase/w1", "a1",
                null, "refunded", "2026-09-22T10:11:12.123456Z", "B", new BigDecimal("50.00"),
                new BigDecimal("670.00"), null, List.of(RelatedPartyRef.customer("party-1")), WithdrawalCaseView.TYPE);
        String row = "{\"id\":\"w1\",\"href\":\"/tmf-api/deviceCommerce/v1/withdrawalCase/w1\",\"agreementRef\":\"a1\","
                + "\"status\":\"refunded\",\"clockStart\":\"2026-09-22T10:11:12.123456Z\",\"returnGrade\":\"B\","
                + "\"deduction\":50.00,\"refundAmount\":670.00,"
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],\"@type\":\"WithdrawalCase\"";
        assertEquals(row + "}", json.writeValueAsString(w));
        assertEquals(row + "}", json.writeValueAsString(WithdrawalReceipt.unchanged(w)));
        assertEquals(row + ",\"subsidyAmount\":null,\"financingModel\":\"OPERATOR_BOOK\",\"currency\":\"EUR\","
                + "\"note\":\"no PSP payment on the agreement — refund recorded, paid out manually\"}",
                json.writeValueAsString(new WithdrawalReceipt(w,
                        new WithdrawalReceipt.AgreementFacts(null, "OPERATOR_BOOK", "EUR"),
                        "no PSP payment on the agreement — refund recorded, paid out manually")));
        assertEquals(row + ",\"subsidyAmount\":240.00,\"financingModel\":\"OPERATOR_BOOK\",\"currency\":\"EUR\"}",
                json.writeValueAsString(new WithdrawalReceipt(w,
                        new WithdrawalReceipt.AgreementFacts(new BigDecimal("240.00"), "OPERATOR_BOOK", "EUR"), null)));
        // the deduction the map wrote from BigDecimal.ZERO stays 0, not 0.00
        assertTrue(json.writeValueAsString(new WithdrawalCaseView("w2", "h", "a1", null, "refunded", "t", null,
                BigDecimal.ZERO, new BigDecimal("729.90"), "REF-1", null, WithdrawalCaseView.TYPE))
                .contains("\"deduction\":0,\"refundAmount\":729.90,\"refundRef\":\"REF-1\",\"@type\""));
    }

    @Test
    void eventPayloads_unchanged() throws Exception {
        assertEquals("{\"agreementId\":\"a1\",\"installmentNo\":24,\"unwindAmount\":10.00,\"currency\":\"EUR\","
                + "\"@type\":\"DeviceInstallment\"}",
                json.writeValueAsString(DeviceInstallmentUnwind.of("a1", 24, new BigDecimal("10.00"), "EUR")));
        assertEquals("{\"tradeInValuationId\":\"v1\",\"amount\":50.00,\"currency\":\"EUR\","
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],\"@type\":\"DeviceChargeRequest\"}",
                json.writeValueAsString(DeviceChargeRequest.of("v1", new BigDecimal("50.00"), "EUR", "party-1")));
        assertEquals("{\"tradeInValuationId\":\"v1\",\"amount\":50.00,\"currency\":\"EUR\",\"@type\":\"DeviceChargeRequest\"}",
                json.writeValueAsString(DeviceChargeRequest.of("v1", new BigDecimal("50.00"), "EUR", null)));
    }

    @Test
    void requests_parseWhatTheChannelsSend_andIgnoreWhatTheyAdd() throws Exception {
        DeviceAgreementRequest a = json.readValue("{\"principal\": 729.9, \"termMonths\": \"24\","
                + " \"financingModel\": \"OPERATOR_BOOK\", \"totalCostOfOwnership\": 720, \"subsidyAmount\": 240,"
                + " \"shippingCost\": 9.90, \"upgradeRule\": {\"paidSharePct\": 50}, \"channel\": \"shop\","
                + " \"relatedParty\": [{\"id\": \"party-1\", \"role\": \"customer\", \"@referredType\": \"Individual\"}]}",
                DeviceAgreementRequest.class);
        assertEquals(new BigDecimal("729.9"), a.principal());
        assertEquals(24, a.termMonths());
        assertEquals("party-1", a.relatedPartyId());
        assertEquals("paidSharePct", a.upgradeRule().type());
        assertEquals(new BigDecimal("9.90"), a.shippingCost());
        assertNull(json.readValue("{\"principal\": 1}", DeviceAgreementRequest.class).relatedPartyId());

        TradeInQuoteRequest t = json.readValue("{\"imei\": \"351 000\", \"deviceRef\": \"phone-x\","
                + " \"conditionAnswers\": {\"ageMonths\": 12, \"screenCracked\": true}, \"channel\": \"shop\"}",
                TradeInQuoteRequest.class);
        assertEquals(12, t.conditionAnswers().get("ageMonths").asInt());
        assertNull(json.readValue("{\"imei\": \"1\"}", TradeInQuoteRequest.class).conditionAnswers());

        assertEquals(new BigDecimal("720"), json.readValue("{\"principal\": 720, \"termMonths\": 24}",
                FinancingTerms.class).principal());
        assertNull(json.readValue("{\"agreementId\": \"a1\"}", WithdrawalRequest.class).deduction());
    }
}
