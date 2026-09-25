package com.bss.revenue;

import com.bss.revenue.dto.AccountNet;
import com.bss.revenue.dto.AccountTotal;
import com.bss.revenue.dto.BackfillReceipt;
import com.bss.revenue.dto.BackfillRequest;
import com.bss.revenue.dto.ChartRow;
import com.bss.revenue.dto.DrillRow;
import com.bss.revenue.dto.JournalEntryView;
import com.bss.revenue.dto.JournalLineView;
import com.bss.revenue.dto.LoyaltyAccrual;
import com.bss.revenue.dto.LoyaltyControl;
import com.bss.revenue.dto.MonthRow;
import com.bss.revenue.dto.PartyRef;
import com.bss.revenue.dto.Period;
import com.bss.revenue.dto.PeriodCloseReceipt;
import com.bss.revenue.dto.PeriodCloseRequest;
import com.bss.revenue.dto.ReconciliationView;
import com.bss.revenue.dto.RemapReceipt;
import com.bss.revenue.dto.RemapRequest;
import com.bss.revenue.dto.RemittanceReceipt;
import com.bss.revenue.dto.RemittanceRequest;
import com.bss.revenue.dto.RevRecRow;
import com.bss.revenue.dto.SubscriptionMetricsView;
import com.bss.revenue.dto.SummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes, the key order and — this being the subledger — the
 * SCALE of every revenue wire record. Money is the BigDecimal that was stored;
 * 49.90 stays 49.90 and 0.00 stays 0.00, never a double's 49.9.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static JournalLineView line(String code, String name, String debit, String credit) {
        return new JournalLineView(0, code, name, new BigDecimal(debit), new BigDecimal(credit),
                "bill-1", "Invoice INV-1");
    }

    @Test
    void anEntryKeepsItsLinesAtTheScaleTheyWereStored() throws Exception {
        JournalEntryView e = new JournalEntryView("e-1", LocalDate.parse("2026-09-23"), "bill:b-1",
                "bill", "Invoice issued — b-1", "EUR", List.of(PartyRef.customer("cust-a")),
                List.of(line("1200", "Accounts receivable", "49.90", "0.00")), "JournalEntry");
        assertThat(write(e)).isEqualTo("{\"id\":\"e-1\",\"entryDate\":\"2026-09-23\","
                + "\"sourceRef\":\"bill:b-1\",\"sourceType\":\"bill\","
                + "\"description\":\"Invoice issued — b-1\",\"currency\":\"EUR\","
                + "\"relatedParty\":[{\"id\":\"cust-a\",\"role\":\"customer\"}],"
                + "\"lines\":[{\"seq\":0,\"accountCode\":\"1200\",\"accountName\":\"Accounts receivable\","
                + "\"debit\":49.90,\"credit\":0.00,\"ref\":\"bill-1\",\"description\":\"Invoice INV-1\"}],"
                + "\"@type\":\"JournalEntry\"}");
    }

    @Test
    void anEntryWithNoPartyOmitsTheReferenceEntirely() throws Exception {
        JournalEntryView e = new JournalEntryView("e-2", LocalDate.parse("2026-09-23"),
                "remittance:klarna:P-1", "remittance", "BNPL remittance", "EUR", null,
                List.of(line("1000", "Cash / PSP clearing", "21.00", "0.00")), "JournalEntry");
        assertThat(write(e)).doesNotContain("relatedParty")
                .contains("\"currency\":\"EUR\",\"lines\":[");
    }

    @Test
    void theTieOutCarriesTheCloseOnlyWhenThereIsOne() throws Exception {
        ReconciliationView base = new ReconciliationView("all", 2, true,
                new BigDecimal("59.55"), new BigDecimal("9947.62"), new BigDecimal("150.49"),
                List.of(new AccountTotal("1000", "Cash / PSP clearing",
                        new BigDecimal("9947.62"), BigDecimal.ZERO)),
                LoyaltyControl.of(1200), null, "RevenueReconciliation");
        assertThat(write(base)).isEqualTo("{\"date\":\"all\",\"entries\":2,\"allEntriesBalanced\":true,"
                + "\"billedTotal\":59.55,\"cashTotal\":9947.62,\"bnplReceivableTotal\":150.49,"
                + "\"byAccount\":[{\"accountCode\":\"1000\",\"accountName\":\"Cash / PSP clearing\","
                + "\"debit\":9947.62,\"credit\":0}],"
                + "\"loyaltyPointsLiability\":{\"points\":1200,\"note\":\"control number — no currency "
                + "valuation configured (see plan P2)\"},\"@type\":\"RevenueReconciliation\"}");
        assertThat(write(base.closedThrough("2026-08-31")))
                .contains("\"loyaltyPointsLiability\":{\"points\":1200")
                .contains("\"closedThrough\":\"2026-08-31\",\"@type\":\"RevenueReconciliation\"}");
        assertThat(write(LoyaltyControl.unreachable()))
                .isEqualTo("{\"note\":\"no loyalty component reachable\"}");
    }

    @Test
    void anAccountTotalAccumulatesWithoutLosingItsName() {
        AccountTotal t = new AccountTotal("4000", "Service revenue", BigDecimal.ZERO, new BigDecimal("10.00"))
                .plus(new BigDecimal("1.50"), new BigDecimal("2.50"));
        assertThat(t.accountName()).isEqualTo("Service revenue");
        assertThat(t.debit()).isEqualByComparingTo("1.50");
        assertThat(t.credit()).isEqualByComparingTo("12.50");
    }

    @Test
    void theSummaryLeadsWithItsTypeAndKeepsANullDelta() throws Exception {
        assertThat(write(new SummaryView("RevenueSummary", new Period("2026-09-01", "2026-09-30"),
                new BigDecimal("100.00"), new BigDecimal("25.00"), new BigDecimal("90.00"), 3,
                BigDecimal.ZERO, null,
                List.of(new AccountNet("4000", "Service revenue", new BigDecimal("100.00"))))))
                .isEqualTo("{\"@type\":\"RevenueSummary\",\"period\":{\"fromDate\":\"2026-09-01\","
                        + "\"toDate\":\"2026-09-30\"},\"netRevenue\":100.00,\"taxCollected\":25.00,"
                        + "\"cashCollected\":90.00,\"invoicesIssued\":3,\"priorNetRevenue\":0,"
                        + "\"revenueDeltaPct\":null,\"byAccount\":[{\"accountCode\":\"4000\","
                        + "\"accountName\":\"Service revenue\",\"net\":100.00}]}");
    }

    @Test
    void theWaterfallWritesItsNullRatesAndItsBaselineFlag() throws Exception {
        MonthRow m = new MonthRow("2026-09", new BigDecimal("100.00"), new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2, new BigDecimal("50.00"),
                null, null, true);
        assertThat(write(m)).isEqualTo("{\"month\":\"2026-09\",\"mrr\":100.00,\"newMrr\":100.00,"
                + "\"expansionMrr\":0,\"contractionMrr\":0,\"churnedMrr\":0,\"activeAccounts\":2,"
                + "\"arpu\":50.00,\"churnRatePct\":null,\"nrrPct\":null,\"baseline\":true}");
        assertThat(write(new DrillRow("2026-09", "cust-a", "expansion",
                new BigDecimal("10.00"), new BigDecimal("60.00"))))
                .isEqualTo("{\"month\":\"2026-09\",\"partyId\":\"cust-a\",\"kind\":\"expansion\","
                        + "\"delta\":10.00,\"mrr\":60.00}");
        assertThat(write(new SubscriptionMetricsView(new Period("2026-09-01", "2026-09-30"),
                "4000", "note", List.of(m), List.of(), "SubscriptionMetrics")))
                .startsWith("{\"period\":{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-30\"},"
                        + "\"accountCode\":\"4000\",\"note\":\"note\",\"months\":[")
                .endsWith("\"drillDown\":[],\"@type\":\"SubscriptionMetrics\"}");
    }

    @Test
    void theReceiptsKeepTheirKeyOrderAndOnlyTheirOwnFacts() throws Exception {
        assertThat(write(new RemittanceReceipt("remittance:klarna:P-1", true)))
                .isEqualTo("{\"sourceRef\":\"remittance:klarna:P-1\",\"posted\":true}");
        assertThat(write(BackfillReceipt.of("b-1", true)))
                .isEqualTo("{\"billId\":\"b-1\",\"posted\":true,\"note\":\"journal entry created\"}");
        assertThat(write(BackfillReceipt.of("b-1", false)))
                .contains("\"note\":\"already journaled — nothing to do\"");
        assertThat(write(PeriodCloseReceipt.of("2026-08-31")))
                .isEqualTo("{\"closedThrough\":\"2026-08-31\",\"note\":\"postings for bills dated on or "
                        + "before this refuse; the export is final\"}");
        assertThat(write(LoyaltyAccrual.skipped("loyalty component unreachable")))
                .isEqualTo("{\"posted\":false,\"note\":\"loyalty component unreachable\"}");
        assertThat(write(LoyaltyAccrual.booked(1200, new BigDecimal("12.00"), new BigDecimal("120.00"))))
                .isEqualTo("{\"posted\":true,\"points\":1200,\"delta\":12.00,\"target\":120.00}");
        assertThat(write(RemapReceipt.of("tax", "2700", "VAT payable", new BigDecimal("25.00"))))
                .isEqualTo("{\"key\":\"tax\",\"accountCode\":\"2700\",\"accountName\":\"VAT payable\","
                        + "\"configValue\":25.00,\"note\":\"applies to FUTURE postings — booked lines "
                        + "keep their snapshot\"}");
        assertThat(write(RemapReceipt.of("ar", "1200", "Accounts receivable", null)))
                .doesNotContain("configValue");
        assertThat(write(new ChartRow("ar", "1200", "Accounts receivable", null, null, null, null)))
                .isEqualTo("{\"key\":\"ar\",\"accountCode\":\"1200\",\"accountName\":\"Accounts receivable\"}");
        assertThat(write(new RevRecRow("a-1", "Contract", "cust-a", null, null,
                "2026-01-01", "2027-01-01", 12, 8, 4, "RevRecInput")))
                .isEqualTo("{\"contractId\":\"a-1\",\"contractName\":\"Contract\",\"partyId\":\"cust-a\","
                        + "\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\","
                        + "\"commitmentMonths\":12,\"monthsElapsed\":8,\"monthsRemaining\":4,"
                        + "\"@type\":\"RevRecInput\"}");
    }

    @Test
    void requestBodiesAcceptWhatTheDesksPostAndTellAbsentFromNull() throws Exception {
        RemittanceRequest r = mapper.readValue("{\"provider\":\"Klarna\",\"reference\":\"P-1\","
                + "\"amount\":{\"unit\":\"EUR\",\"value\":21.00},\"extra\":1}", RemittanceRequest.class);
        assertThat(r.provider()).isEqualTo("Klarna");
        assertThat(r.amount().value()).isEqualByComparingTo("21.00");
        assertThat(r.amount().unit()).isEqualTo("EUR");

        assertThat(mapper.readValue("{\"billId\":\"b-1\"}", BackfillRequest.class).billId()).isEqualTo("b-1");
        assertThat(mapper.readValue("{}", BackfillRequest.class).billId()).isNull();
        assertThat(mapper.readValue("{\"through\":\"2026-08-31\"}", PeriodCloseRequest.class).through())
                .isEqualTo("2026-08-31");

        RemapRequest absent = mapper.readValue("{\"key\":\"tax\",\"accountCode\":\"2700\","
                + "\"accountName\":\"VAT\"}", RemapRequest.class);
        assertThat(absent.configValue()).isNull();                 // leave the setting alone
        RemapRequest cleared = mapper.readValue("{\"configValue\":null}", RemapRequest.class);
        assertThat(cleared.configValue().isNull()).isTrue();       // clear it
        RemapRequest set = mapper.readValue("{\"configValue\":\"25\"}", RemapRequest.class);
        assertThat(new BigDecimal(set.configValue().asText())).isEqualByComparingTo("25");
    }
}
