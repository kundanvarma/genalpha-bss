package com.bss.revenue.service;

import com.bss.revenue.client.BillingClient;
import com.bss.revenue.dto.AccountNet;
import com.bss.revenue.dto.AccountTotal;
import com.bss.revenue.dto.BackfillReceipt;
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
import com.bss.revenue.dto.ReconciliationView;
import com.bss.revenue.dto.RemapReceipt;
import com.bss.revenue.dto.RemapRequest;
import com.bss.revenue.dto.RemittanceReceipt;
import com.bss.revenue.dto.RemittanceRequest;
import com.bss.revenue.dto.RevRecRow;
import com.bss.revenue.dto.SubscriptionMetricsView;
import com.bss.revenue.dto.SummaryView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bss.revenue.entity.AccountMapping;
import com.bss.revenue.entity.JournalEntry;
import com.bss.revenue.entity.JournalLine;
import com.bss.revenue.entity.PeriodClose;
import com.bss.revenue.exception.BadRequestException;
import com.bss.revenue.exception.NotFoundException;
import com.bss.revenue.repository.AccountMappingRepository;
import com.bss.revenue.repository.JournalEntryRepository;
import com.bss.revenue.repository.JournalLineRepository;
import com.bss.revenue.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The subledger. Billing and payment events become balanced double-entry
 * journal postings against a per-tenant chart-of-accounts mapping; the
 * export is the file a period-close import wants; the reconciliation is
 * the tie-out finance runs before trusting either system. Balance is a
 * SAVE-TIME INVARIANT — an unbalanced entry refuses to exist. The GL
 * itself lives in the ERP; this feed is what it ingests.
 */
@Service
public class RevenueService {

    private static final Logger log = LoggerFactory.getLogger(RevenueService.class);

    /** The editable default chart — finance renames, history keeps snapshots. */
    private static final Map<String, String[]> DEFAULT_CHART = new LinkedHashMap<>();
    static {
        DEFAULT_CHART.put("ar", new String[] {"1200", "Accounts receivable"});
        DEFAULT_CHART.put("cash", new String[] {"1000", "Cash / PSP clearing"});
        // BNPL (Klarna &c.) pays the merchant on its OWN settlement cycle, so a
        // capture is a receivable FROM the provider, not cash — cleared to 1000
        // when the provider remits. Keeps the cash line honest for BNPL orders.
        DEFAULT_CHART.put("bnpl:receivable", new String[] {"1100", "BNPL / provider clearing"});
        DEFAULT_CHART.put("rate:recurringCharge", new String[] {"4000", "Service revenue"});
        DEFAULT_CHART.put("rate:usageCharge", new String[] {"4010", "Usage revenue"});
        DEFAULT_CHART.put("rate:discount", new String[] {"4090", "Discounts (contra-revenue)"});
        DEFAULT_CHART.put("rate:priceAdjustment", new String[] {"4091", "Pricing adjustments"});
        DEFAULT_CHART.put("rate:disputeCredit", new String[] {"4092", "Dispute credits (contra-revenue)"});
        DEFAULT_CHART.put("dispute", new String[] {"4092", "Dispute credits (contra-revenue)"});
        DEFAULT_CHART.put("rate:creditNote", new String[] {"4093", "Credit notes (contra-revenue)"});
        DEFAULT_CHART.put("creditNote", new String[] {"4093", "Credit notes (contra-revenue)"});
        DEFAULT_CHART.put("refund", new String[] {"4095", "Refunds (contra-revenue)"});
        // config_value on 'tax' = VAT percent (prices are tax-INCLUSIVE; 0/absent = no split)
        DEFAULT_CHART.put("tax", new String[] {"2700", "VAT payable"});
        DEFAULT_CHART.put("loyalty:expense", new String[] {"6100", "Loyalty program expense"});
        // config_value on 'loyalty:liability' = currency per point (0/absent = control number only)
        DEFAULT_CHART.put("loyalty:liability", new String[] {"2400", "Loyalty points liability"});
        // open access: the wholesale fibre we buy is a cost of sale; what we owe
        // the owner is a payable until the settlement clears
        DEFAULT_CHART.put("wholesale:cogs", new String[] {"5100", "Wholesale access (COGS)"});
        DEFAULT_CHART.put("wholesale:payable", new String[] {"2100", "Accounts payable — wholesale"});
        DEFAULT_CHART.put("mobile-wholesale:cogs", new String[] {"5110", "Mobile wholesale usage (COGS)"});
        DEFAULT_CHART.put("mobile-wholesale:payable", new String[] {"2110", "Accounts payable — mobile wholesale"});
        DEFAULT_CHART.put("mobile-wholesale:receivable", new String[] {"1210", "Accounts receivable — mobile wholesale"});
        DEFAULT_CHART.put("club-share:expense", new String[] {"6150", "Community sponsorship (Klubbdugnad)"});
        DEFAULT_CHART.put("club-share:payable", new String[] {"2150", "Payable to community clubs"});
        DEFAULT_CHART.put("mobile-wholesale:revenue", new String[] {"4020", "Mobile wholesale revenue"});
        // device commerce: IFRS 15 puts more equipment revenue at delivery than
        // cash received on subsidised operator-book bundles — the gap is a
        // contract asset unwound monthly against service billings. Bank/BNPL
        // programs recognise fully at payout and accrue any residual-value
        // guarantee as a liability. Trade-ins arrive as inventory.
        DEFAULT_CHART.put("device:contract-asset", new String[] {"1250", "Device contract asset (IFRS 15)"});
        DEFAULT_CHART.put("device:equipment-revenue", new String[] {"4030", "Equipment revenue — devices"});
        DEFAULT_CHART.put("device:trade-in-inventory", new String[] {"1300", "Trade-in device inventory"});
        DEFAULT_CHART.put("device:swap-writeoff", new String[] {"5210", "Device swap write-off"});
        DEFAULT_CHART.put("device:deduction", new String[] {"4040", "Diminished-value recovery"});
        DEFAULT_CHART.put("device:rvg-expense", new String[] {"6200", "Residual-value guarantee expense"});
        DEFAULT_CHART.put("device:rvg-liability", new String[] {"2500", "Residual-value guarantee liability"});
    }

    /** PSPs whose capture is a receivable (deferred settlement), not immediate cash.
     * Klarna and other BNPL remit later; card/instant PSPs clear to cash at capture.
     * (A production system would carry the settlement timing as provider config;
     * here it is a small, explicit list — the honest boundary.) */
    private static final Set<String> BNPL_PROVIDERS = Set.of("klarna");

    private static final TypeReference<Map<String, Object>> OPEN_MAP = new TypeReference<>() { };

    private final ObjectMapper json = new ObjectMapper();

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final AccountMappingRepository mappings;
    private final BillingClient billingClient;
    private final TenantScope tenantScope;
    private final com.bss.revenue.repository.PeriodCloseRepository periods;

    public RevenueService(JournalEntryRepository entries, JournalLineRepository lines,
            AccountMappingRepository mappings, BillingClient billingClient, TenantScope tenantScope,
            com.bss.revenue.repository.PeriodCloseRepository periods) {
        this.entries = entries;
        this.lines = lines;
        this.mappings = mappings;
        this.billingClient = billingClient;
        this.tenantScope = tenantScope;
        this.periods = periods;
    }

    /* ---------- posting builders ---------- */

    /** Invoice issued: debit AR for the bill total, credit revenue per line. */
    @Transactional
    public boolean postBill(String billId, Map<String, Object> billEvent) {
        String tenant = tenantScope.currentTenantId();
        String sourceRef = "bill:" + billId;
        requireOpenPeriod(tenant, billEvent.get("billDate"));
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        List<Map<String, Object>> rates = billingClient.ratesOf(billId);
        if (rates == null || rates.isEmpty()) {
            throw new BadRequestException("bill " + billId + " has no rate lines yet");
        }
        Map<String, Object> amountDue = billEvent.get("amountDue") instanceof Map<?, ?> m
                ? castMap(m) : castMap(asMap(billingClient.bill(billId)).get("amountDue"));
        BigDecimal total = money(amountDue.get("value"));
        String currency = amountDue.get("unit") == null ? "EUR" : String.valueOf(amountDue.get("unit"));
        String party = partyOf(billEvent);

        // prices are tax-INCLUSIVE by convention; a configured VAT percent
        // splits each line into net revenue + one tax-payable credit. The tax
        // line is computed as gross-minus-sum-of-nets so rounding can never
        // unbalance the entry.
        BigDecimal taxPct = configValueOf("tax");
        List<JournalLine> posting = new ArrayList<>();
        posting.add(line("ar", total, null, billId, "Invoice " + billEvent.getOrDefault("billNo", billId)));
        BigDecimal netSum = BigDecimal.ZERO;
        boolean anyTax = false;
        for (Map<String, Object> rate : rates) {
            String type = String.valueOf(rate.getOrDefault("type", "recurringCharge"));
            BigDecimal amount = money(rate.get("taxExcludedAmount") instanceof Map<?, ?> a
                    ? castMap(a).get("value") : null);
            if (amount.signum() == 0) {
                continue;
            }
            // TMF678 appliedTax on the line wins (a zero-rated line says 0 explicitly);
            // a line that says nothing carries the tenant's default rate.
            BigDecimal linePct = taxPct;
            if (rate.get("appliedTax") instanceof List<?> taxes) {
                for (Object t : taxes) {
                    if (t instanceof Map<?, ?> tm && tm.get("taxRate") != null) {
                        linePct = new BigDecimal(String.valueOf(tm.get("taxRate")));
                    }
                }
            }
            anyTax = anyTax || linePct.signum() > 0;
            BigDecimal net = linePct.signum() > 0
                    ? amount.divide(BigDecimal.ONE.add(linePct.movePointLeft(2)), 2, RoundingMode.HALF_UP) : amount;
            netSum = netSum.add(net);
            // discounts arrive NEGATIVE: a negative credit is a debit to contra-revenue
            String key = DEFAULT_CHART.containsKey("rate:" + type) ? "rate:" + type : "rate:priceAdjustment";
            posting.add(net.signum() < 0
                    ? line(key, net.negate(), null, billId, String.valueOf(rate.get("name")))
                    : line(key, null, net, billId, String.valueOf(rate.get("name"))));
        }
        if (anyTax) {
            BigDecimal tax = total.subtract(netSum);
            if (tax.signum() != 0) {
                posting.add(line("tax", null, tax, billId, "VAT (tax-inclusive prices; per-line rates, default "
                        + taxPct.stripTrailingZeros().toPlainString() + "%)"));
            }
        }
        saveBalanced(tenant, sourceRef, "bill", "Invoice issued — " + billId, currency, party, posting);
        return true;
    }

    /** Cash received (capture or recorded external payment): debit cash, credit AR. */
    @Transactional
    public boolean postCash(String paymentId, String status, Map<String, Object> payment) {
        String tenant = tenantScope.currentTenantId();
        String sourceRef = "payment:" + paymentId + ":" + status;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        Map<String, Object> amount = castMap(payment.get("amount"));
        BigDecimal value = money(amount.get("value"));
        if (value.signum() <= 0) {
            return false;
        }
        String currency = amount.get("unit") == null ? "EUR" : String.valueOf(amount.get("unit"));
        // BNPL captures debit a receivable-from-provider (Klarna remits later);
        // card/instant captures debit cash. Either way AR is relieved.
        String provider = payment.get("pspProvider") == null ? null
                : String.valueOf(payment.get("pspProvider")).toLowerCase();
        boolean bnpl = provider != null && BNPL_PROVIDERS.contains(provider);
        String debitKey = bnpl ? "bnpl:receivable" : "cash";
        String debitDesc = bnpl ? "BNPL capture (" + provider + ") — " + paymentId : "Payment " + paymentId;
        List<JournalLine> posting = List.of(
                line(debitKey, value, null, paymentId, debitDesc),
                line("ar", null, value, paymentId, "Payment applied"));
        saveBalanced(tenant, sourceRef, "payment",
                (bnpl ? "BNPL receivable — " : "Cash received — ") + paymentId, currency,
                ownerOf(payment),
                posting);
        return true;
    }

    /**
     * Open access: a wholesale access line went live, so the fibre we buy from the
     * owner is a cost of sale. Accrue it — DEBIT wholesale COGS / CREDIT accounts
     * payable to the owner — so the retail margin (revenue minus this) is real in
     * the ledger, not just the settlement view. Idempotent by the access-order id;
     * the owner is carried on the line for a per-owner payable breakdown.
     */
    @Transactional
    public boolean postWholesaleCogs(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "wholesale:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("ratePerLine") == null) {
            return false; // no rate on the event — nothing to book (fail-soft)
        }
        BigDecimal rate = money(event.get("ratePerLine"));
        if (rate.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String owner = String.valueOf(event.getOrDefault("accessOwner", "owner"));
        String layer = String.valueOf(event.getOrDefault("accessLayer", ""));
        String desc = "Wholesale access " + owner + (layer.isBlank() ? "" : " (" + layer + ")");
        List<JournalLine> posting = List.of(
                line("wholesale:cogs", rate, null, id, desc + " — monthly"),
                line("wholesale:payable", null, rate, id, "Payable to " + owner));
        saveBalanced(tenant, sourceRef, "wholesaleCogs", desc + " — " + id, currency, null, posting);
        return true;
    }

    /**
     * Mobile wholesale (MVNE): a wholesale usage-ledger row is what the MVNO owes
     * its host for a usage type this period — a usage-metered cost of sale. Booked
     * as COGS + a payable to the host, keyed on the ledger id so replays are free.
     */
    @Transactional
    public boolean postMobileWholesaleCogs(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "mobile-wholesale:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("amount") == null) {
            return false; // no amount on the event — nothing to book (fail-soft)
        }
        BigDecimal amount = money(event.get("amount"));
        if (amount.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String spec = String.valueOf(event.getOrDefault("usageSpecName", "usage"));
        String period = String.valueOf(event.getOrDefault("periodStart", ""));
        String desc = "Mobile wholesale " + spec + (period.isBlank() ? "" : " " + period);
        List<JournalLine> posting = List.of(
                line("mobile-wholesale:cogs", amount, null, id, desc),
                line("mobile-wholesale:payable", null, amount, id, "Payable to host MNO"));
        saveBalanced(tenant, sourceRef, "mobileWholesaleCogs", desc + " — " + id, currency, null, posting);
        return true;
    }

    /**
     * Late-CDR re-rate (the CLOSED LOOP): the wholesale ledger row moved after
     * it was first booked — the event carries the DELTA. A positive delta books
     * additional COGS + payable; a negative one reverses the excess. Keyed on
     * (ledger id, rerateCount) so replays are free and every re-rate books once.
     */
    @Transactional
    public boolean postMobileWholesaleCogsDelta(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        Object count = event.getOrDefault("rerateCount", 0);
        String sourceRef = "mobile-wholesale-rerate:" + id + ":" + count;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("delta") == null) {
            return false;
        }
        BigDecimal delta = money(event.get("delta"));
        if (delta.signum() == 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String spec = String.valueOf(event.getOrDefault("usageSpecName", "usage"));
        String period = String.valueOf(event.getOrDefault("periodStart", ""));
        String desc = "Mobile wholesale re-rate #" + count + " " + spec
                + (period.isBlank() ? "" : " " + period);
        BigDecimal magnitude = delta.abs();
        List<JournalLine> posting = delta.signum() > 0
                ? List.of(line("mobile-wholesale:cogs", magnitude, null, id, desc),
                          line("mobile-wholesale:payable", null, magnitude, id, "Payable to host MNO"))
                : List.of(line("mobile-wholesale:payable", magnitude, null, id, "Payable to host MNO reduced"),
                          line("mobile-wholesale:cogs", null, magnitude, id, desc));
        saveBalanced(tenant, sourceRef, "mobileWholesaleCogsDelta", desc + " — " + id, currency, null, posting);
        return true;
    }

    /**
     * Provider-face closed loop (W-M7): the mediation feed re-reported an
     * external MVNO's period and the rated row MOVED — the event carries the
     * delta. A positive delta books additional receivable + revenue; a
     * negative one reverses the excess. Keyed on (ledger id, rerateCount)
     * so replays are free and every correction books exactly once.
     */
    @Transactional
    public boolean postMobileWholesaleRevenueDelta(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        Object count = event.getOrDefault("rerateCount", 0);
        String sourceRef = "mobile-wholesale-provider-rerate:" + id + ":" + count;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("delta") == null) {
            return false;
        }
        BigDecimal delta = money(event.get("delta"));
        if (delta.signum() == 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String mvno = String.valueOf(event.getOrDefault("mvnoName", event.getOrDefault("mvnoPartyId", "MVNO")));
        String spec = String.valueOf(event.getOrDefault("usageSpecName", "usage"));
        String period = String.valueOf(event.getOrDefault("periodStart", ""));
        String desc = "Mobile wholesale provider re-rate #" + count + " " + mvno + " " + spec
                + (period.isBlank() ? "" : " " + period);
        BigDecimal magnitude = delta.abs();
        List<JournalLine> posting = delta.signum() > 0
                ? List.of(line("mobile-wholesale:receivable", magnitude, null, id, desc),
                          line("mobile-wholesale:revenue", null, magnitude, id, "Wholesale revenue — " + mvno))
                : List.of(line("mobile-wholesale:revenue", magnitude, null, id, "Wholesale revenue reduced — " + mvno),
                          line("mobile-wholesale:receivable", null, magnitude, id, desc));
        saveBalanced(tenant, sourceRef, "mobileWholesaleRevenueDelta", desc + " — " + id, currency, null, posting);
        return true;
    }

    /**
     * H2 — KLUBBDUGNAD becomes MONEY: a rewarded, club-linked referral accrues
     * the club's share as a real liability — sponsorship expense against a
     * payable to the club, keyed on the conversion so replays are free. The
     * season tally stops being a scoreboard and becomes a balance the
     * operator OWES — which is the whole point of a dugnad.
     */
    @Transactional
    public boolean postClubShare(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawConversionId = event.get("conversionId");
        if (rawConversionId == null) {
            return false;   // no conversionId on the event — nothing to key a posting by
        }
        String conversionId = rawConversionId.toString();
        String sourceRef = "club-share:" + conversionId;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("amount") == null) {
            return false;
        }
        BigDecimal amount = money(event.get("amount"));
        if (amount.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String club = String.valueOf(event.getOrDefault("clubOrgId", "club"));
        String desc = "Klubbdugnad share — club " + club;
        List<JournalLine> posting = List.of(
                line("club-share:expense", amount, null, conversionId, desc),
                line("club-share:payable", null, amount, conversionId, "Payable to club " + club));
        saveBalanced(tenant, sourceRef, "clubShare", desc + " — " + conversionId, currency, null, posting);
        return true;
    }

    /**
     * Mobile wholesale PROVIDER side (W-M7): the host earns wholesale revenue from
     * an external MVNO's traffic — booked as a receivable + wholesale revenue,
     * keyed on the provider-ledger id so replays are free. The mirror of the
     * seeker's COGS: seeker owes (COGS), host earns (revenue).
     */
    @Transactional
    public boolean postMobileWholesaleRevenue(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "mobile-wholesale-rev:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (event.get("amount") == null) {
            return false;
        }
        BigDecimal amount = money(event.get("amount"));
        if (amount.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        String mvno = String.valueOf(event.getOrDefault("mvnoName", event.getOrDefault("mvnoPartyId", "MVNO")));
        String spec = String.valueOf(event.getOrDefault("usageSpecName", "usage"));
        String desc = "Mobile wholesale — " + mvno + " " + spec;
        List<JournalLine> posting = List.of(
                line("mobile-wholesale:receivable", amount, null, id, "Receivable from " + mvno),
                line("mobile-wholesale:revenue", null, amount, id, desc));
        saveBalanced(tenant, sourceRef, "mobileWholesaleRevenue", desc + " — " + id, currency, null, posting);
        return true;
    }

    /* ---------- device commerce: the subsidy subledger ---------- */

    /**
     * Operator-book device agreement activated with a subsidy: IFRS 15 books
     * MORE equipment revenue at delivery than the instalments will collect —
     * the gap is a contract asset. DR 1250 contract asset / CR 4030 equipment
     * revenue for the subsidy amount; the monthly instalment feed unwinds it.
     * Idempotent by agreement id. Bank/BNPL agreements post at payout instead.
     */
    @Transactional
    public boolean postDeviceAgreementActivated(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "device-activation:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (!"OPERATOR_BOOK".equals(event.get("financingModel"))) {
            return false;   // payout-time recognition for bank/BNPL models
        }
        BigDecimal subsidy = money(event.get("subsidyAmount"));
        if (subsidy.signum() <= 0) {
            return false;   // unsubsidised instalments: nothing beyond normal billing
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = List.of(
                line("device:contract-asset", subsidy, null, id, "Device subsidy at delivery"),
                line("device:equipment-revenue", null, subsidy, id, "Equipment revenue allocation (IFRS 15)"));
        saveBalanced(tenant, sourceRef, "deviceActivation",
                "Device agreement activated — " + id, currency, partyOf(event), posting);
        return true;
    }

    /**
     * One instalment landed on a subsidised operator-book agreement: part of
     * the month's service billing settles the contract asset instead of being
     * revenue — DR 4000 service revenue (reallocation) / CR 1250 contract
     * asset. Keyed on (agreement, instalment no) so replays are free; the
     * emitter gives the last instalment the rounding remainder.
     */
    @Transactional
    public boolean postDeviceContractAssetUnwind(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("agreementId");
        if (rawId == null) {
            return false;   // no agreementId on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        Object no = event.getOrDefault("installmentNo", 0);
        String sourceRef = "device-unwind:" + id + ":" + no;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        BigDecimal amount = money(event.get("unwindAmount"));
        if (amount.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = List.of(
                line("rate:recurringCharge", amount, null, id, "Contract-asset unwind #" + no),
                line("device:contract-asset", null, amount, id, "Device subsidy unwound"));
        saveBalanced(tenant, sourceRef, "deviceUnwind",
                "Device contract-asset unwind #" + no + " — " + id, currency, null, posting);
        return true;
    }

    /**
     * Early termination: the ETF economically recovers the UNEARNED subsidy —
     * DR AR for the fee, CR contract asset up to what remains on the book,
     * any excess to equipment revenue (never a negative asset).
     */
    @Transactional
    public boolean postDeviceEtf(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "device-etf:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        BigDecimal etf = money(event.get("etfAmount"));
        if (etf.signum() <= 0) {
            return false;   // schedule completed normally — nothing to recover
        }
        BigDecimal remaining = money(event.get("remainingSubsidy"));
        BigDecimal offset = etf.min(remaining.signum() > 0 ? remaining : BigDecimal.ZERO);
        BigDecimal excess = etf.subtract(offset);
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = new ArrayList<>();
        posting.add(line("ar", etf, null, id, "Early-termination fee"));
        if (offset.signum() > 0) {
            posting.add(line("device:contract-asset", null, offset, id, "Unearned subsidy recovered"));
        }
        if (excess.signum() > 0) {
            posting.add(line("device:equipment-revenue", null, excess, id, "ETF beyond remaining subsidy"));
        }
        saveBalanced(tenant, sourceRef, "deviceEtf", "Device ETF — " + id, currency, partyOf(event), posting);
        return true;
    }

    /**
     * Swap (operator-book): remaining instalments write off against the
     * trade-in device received — DR 1300 inventory at graded value, DR 5210
     * write-off for the shortfall, CR the device receivable; a trade-in worth
     * MORE than the remainder credits equipment revenue. Any subsidy still on
     * the book reverses in the same entry. Bank/BNPL swaps settle with the
     * financier, not this ledger.
     */
    @Transactional
    public boolean postDeviceSwap(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "device-swap:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (!"OPERATOR_BOOK".equals(event.get("financingModel"))) {
            return false;
        }
        Map<String, Object> settlement = castMap(event.get("settlement"));
        BigDecimal remaining = money(settlement.get("remainingPrincipal"));
        BigDecimal tradeIn = money(settlement.get("tradeInValue"));
        if (remaining.signum() <= 0 && tradeIn.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = new ArrayList<>();
        if (tradeIn.signum() > 0) {
            posting.add(line("device:trade-in-inventory", tradeIn, null, id, "Trade-in device received"));
        }
        BigDecimal writeOff = remaining.subtract(tradeIn);
        if (writeOff.signum() > 0) {
            posting.add(line("device:swap-writeoff", writeOff, null, id, "Instalments written off on swap"));
        }
        if (remaining.signum() > 0) {
            posting.add(line("ar", null, remaining, id, "Device receivable extinguished"));
        }
        if (writeOff.signum() < 0) {
            posting.add(line("device:equipment-revenue", null, writeOff.negate(), id,
                    "Trade-in above remaining instalments"));
        }
        BigDecimal remainingSubsidy = money(event.get("remainingSubsidy"));
        if (remainingSubsidy.signum() > 0) {
            posting.add(line("device:equipment-revenue", remainingSubsidy, null, id,
                    "Unearned subsidy reversed on swap"));
            posting.add(line("device:contract-asset", null, remainingSubsidy, id,
                    "Contract asset cleared on swap"));
        }
        saveBalanced(tenant, sourceRef, "deviceSwap", "Device swap — " + id, currency,
                partyOf(event), posting);
        return true;
    }

    /**
     * Third-party payout: the bank paid the operator out upfront — full
     * equipment-revenue recognition at payout (DR cash / CR 4030), plus a
     * residual-value guarantee accrual when the program promises a buy-back
     * (DR 6200 / CR 2500). Idempotent by agreement id.
     */
    @Transactional
    public boolean postDevicePayout(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawId = event.get("id");
        if (rawId == null) {
            return false;   // no id on the event — nothing to key a posting by
        }
        String id = rawId.toString();
        String sourceRef = "device-payout:" + id;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        if (!"THIRD_PARTY_LOAN".equals(event.get("financingModel"))) {
            return false;   // BNPL captures already book through the payment path
        }
        BigDecimal principal = money(event.get("principal"));
        if (principal.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = new ArrayList<>();
        posting.add(line("cash", principal, null, id, "Financier payout received"));
        posting.add(line("device:equipment-revenue", null, principal, id,
                "Equipment revenue at payout (financier owns the receivable)"));
        BigDecimal residual = money(event.get("residualValue"));
        if (residual.signum() > 0) {
            posting.add(line("device:rvg-expense", residual, null, id, "Residual-value guarantee accrual"));
            posting.add(line("device:rvg-liability", null, residual, id,
                    "Buy-back promised to the financier"));
        }
        saveBalanced(tenant, sourceRef, "devicePayout", "Financing payout — " + id, currency,
                partyOf(event), posting);
        return true;
    }

    /**
     * Withdrawal (angrerett): the subsidised delivery-time recognition
     * reverses (DR 4030 / CR 1250); a documented diminished-value deduction
     * is the operator's to keep (DR AR / CR 4040). The cash refund itself
     * books through the payment component's refund event — one path, never
     * two. Keyed on the agreement so the case replays free.
     */
    @Transactional
    public boolean postDeviceWithdrawal(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        Object rawAgreementRef = event.get("agreementRef");
        if (rawAgreementRef == null) {
            return false;   // no agreementRef on the event — nothing to key a posting by
        }
        String agreementRef = rawAgreementRef.toString();
        String sourceRef = "device-withdrawal:" + agreementRef;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        BigDecimal subsidy = "OPERATOR_BOOK".equals(event.get("financingModel"))
                ? money(event.get("subsidyAmount")) : BigDecimal.ZERO;
        BigDecimal deduction = money(event.get("deduction"));
        if (subsidy.signum() <= 0 && deduction.signum() <= 0) {
            return false;
        }
        String currency = event.get("currency") == null ? "EUR" : String.valueOf(event.get("currency"));
        List<JournalLine> posting = new ArrayList<>();
        if (subsidy.signum() > 0) {
            posting.add(line("device:equipment-revenue", subsidy, null, agreementRef,
                    "Delivery-time recognition reversed"));
            posting.add(line("device:contract-asset", null, subsidy, agreementRef,
                    "Contract asset cleared on withdrawal"));
        }
        if (deduction.signum() > 0) {
            posting.add(line("ar", deduction, null, agreementRef, "Diminished-value deduction"));
            posting.add(line("device:deduction", null, deduction, agreementRef,
                    "Documented diminished value retained"));
        }
        saveBalanced(tenant, sourceRef, "deviceWithdrawal", "Device withdrawal — " + agreementRef,
                currency, partyOf(event), posting);
        return true;
    }

    /** BNPL provider remittance — the provider paid the merchant out, so the
     * receivable booked at capture clears to cash: DEBIT cash / CREDIT 1100.
     * Idempotent by the provider's remittance reference (a payout file replayed
     * twice books once). The amount is the payout total; matching individual
     * captures inside it is the provider's statement's job, not the ledger's. */
    @Transactional
    public RemittanceReceipt postRemittance(RemittanceRequest dto) {
        String tenant = tenantScope.currentTenantId();
        String provider = dto.provider() == null ? null : dto.provider().toLowerCase();
        String reference = dto.reference();
        if (provider == null || provider.isBlank() || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("provider and reference are required");
        }
        if (!BNPL_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("'" + provider + "' is not a BNPL provider — nothing to clear");
        }
        BigDecimal value = dto.amount() == null ? BigDecimal.ZERO : money(dto.amount().value());
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("amount.value must be positive");
        }
        String currency = dto.amount() == null || dto.amount().unit() == null ? "EUR" : dto.amount().unit();
        String sourceRef = "remittance:" + provider + ":" + reference;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return new RemittanceReceipt(sourceRef, false);   // replayed payout file — already booked
        }
        List<JournalLine> posting = List.of(
                line("cash", value, null, reference, "Remittance " + provider + " " + reference),
                line("bnpl:receivable", null, value, reference, "BNPL receivable cleared"));
        saveBalanced(tenant, sourceRef, "remittance",
                "BNPL remittance — " + provider + " " + reference, currency, null, posting);
        return new RemittanceReceipt(sourceRef, true);
    }

    /** Refund out the door: debit contra-revenue, credit cash. */
    @Transactional
    public boolean postRefund(Map<String, Object> refund) {
        String tenant = tenantScope.currentTenantId();
        String ref = String.valueOf(refund.getOrDefault("refundRef",
                refund.getOrDefault("paymentId", UUID.randomUUID().toString())));
        String sourceRef = "refund:" + ref;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        Map<String, Object> amount = castMap(refund.get("amount"));
        BigDecimal value = money(amount.get("value"));
        if (value.signum() <= 0) {
            return false;
        }
        String currency = amount.get("unit") == null ? "EUR" : String.valueOf(amount.get("unit"));
        List<JournalLine> posting = List.of(
                line("refund", value, null, ref, "Refund " + ref),
                line("cash", null, value, ref, "Refund paid out"));
        saveBalanced(tenant, sourceRef, "refund", "Refund — " + ref, currency, partyOf(refund), posting);
        return true;
    }

    /** Idempotent onboarding of a pre-arc bill (and the suite's replay probe). */
    @Transactional
    public BackfillReceipt backfill(String billId) {
        Map<String, Object> bill = asMap(billingClient.bill(billId));
        boolean posted = postBill(billId, bill);   // close guard runs inside
        return BackfillReceipt.of(billId, posted);
    }

    /** A credit note on an UNPAID bill: contra-revenue against AR, under
     * the document's own number. (A REFUNDED credit note moved money via
     * the PSP — the refund event books that path; one path, never two.) */
    @Transactional
    public boolean postCreditNote(String creditNoteId, Map<String, Object> creditNote) {
        String tenant = tenantScope.currentTenantId();
        String sourceRef = "creditNote:" + creditNoteId;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        BigDecimal amount = money(creditNote.get("amount") instanceof Map<?, ?> a
                ? castMap(a).get("value") : null);
        if (amount.signum() <= 0) {
            return false;
        }
        String no = String.valueOf(creditNote.getOrDefault("creditNoteNo", creditNoteId));
        String billNo = String.valueOf(creditNote.getOrDefault("billNo", ""));
        List<JournalLine> posting = List.of(
                line("creditNote", amount, null, no,
                        no + " — " + creditNote.getOrDefault("reason", "credit")),
                line("ar", null, amount, no, "Credits invoice " + billNo));
        saveBalanced(tenant, sourceRef, "creditNote", "Credit note " + no + " (credits " + billNo + ")",
                String.valueOf(castMap(creditNote.get("amount")).getOrDefault("unit", "EUR")),
                partyOf(creditNote), posting);
        return true;
    }

    /** Points priced into currency — ONLY when finance sets a per-point value.
     * Books the DELTA between the loyalty component's live liability and what
     * this journal already carries, one accrual per day (fleet-safe tick or
     * on-demand). No value configured = control number only, nothing booked. */
    @Transactional
    public LoyaltyAccrual loyaltyAccrual() {
        String tenant = tenantScope.currentTenantId();
        BigDecimal perPoint = configValueOf("loyalty:liability");
        if (perPoint.signum() <= 0) {
            return LoyaltyAccrual.skipped(
                    "no currency-per-point configured on loyalty:liability — points stay a control number");
        }
        Long points = billingClient.loyaltyPointsLiability();
        if (points == null) {
            return LoyaltyAccrual.skipped("loyalty component unreachable");
        }
        String liabilityCode = mappings.findByTenantIdAndMappingKey(tenant, "loyalty:liability")
                .orElseThrow().getAccountCode();
        BigDecimal booked = BigDecimal.ZERO;
        for (JournalEntry e : entries.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)) {
            for (JournalLine l : lines.findAllByTenantIdAndEntryIdOrderBySeqAsc(tenant, e.getId())) {
                if (liabilityCode.equals(l.getAccountCode())) {
                    booked = booked.add(l.getCredit()).subtract(l.getDebit());
                }
            }
        }
        BigDecimal target = perPoint.multiply(BigDecimal.valueOf(points)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal delta = target.subtract(booked);
        if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) {
            return LoyaltyAccrual.skipped("booked liability already matches "
                    + points + " pts x " + perPoint);
        }
        String sourceRef = "loyalty-accrual:" + LocalDate.now();
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return LoyaltyAccrual.skipped("already accrued today — daily cadence");
        }
        List<JournalLine> posting = delta.signum() > 0
                ? List.of(line("loyalty:expense", delta, null, "loyalty", "Points liability accrual"),
                          line("loyalty:liability", null, delta, "loyalty",
                                  points + " pts x " + perPoint + "/pt"))
                : List.of(line("loyalty:liability", delta.negate(), null, "loyalty",
                                  "Points redeemed/expired — liability release"),
                          line("loyalty:expense", null, delta.negate(), "loyalty", "Accrual release"));
        saveBalanced(tenant, sourceRef, "loyalty", "Loyalty points accrual — " + points + " pts",
                "EUR", null, posting);
        return LoyaltyAccrual.booked(points, delta, target);
    }

    /** Close = a completeness attestation: balanced (invariant) and FINAL —
     * postings for bills dated inside a closed period refuse with 409. */
    @Transactional
    public PeriodCloseReceipt closePeriod(String through) {
        String tenant = tenantScope.currentTenantId();
        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(through));
        } catch (Exception e) {
            throw new BadRequestException("through must be YYYY-MM-DD");
        }
        PeriodClose close = periods.findById(tenant).orElseGet(() -> {
            PeriodClose c = new PeriodClose();
            c.setTenantId(tenant);
            return c;
        });
        close.setClosedThrough(date);
        close.setClosedAt(OffsetDateTime.now());
        periods.save(close);
        return PeriodCloseReceipt.of(date.toString());
    }

    private void requireOpenPeriod(String tenant, Object billDate) {
        LocalDate closed = periods.findById(tenant)
                .map(PeriodClose::getClosedThrough).orElse(null);
        if (closed == null || billDate == null) {
            return;
        }
        try {
            LocalDate d = LocalDate.parse(String.valueOf(billDate).substring(0, 10));
            if (!d.isAfter(closed)) {
                throw new com.bss.revenue.exception.ConflictException("period closed through "
                        + closed + " — this bill is dated " + d + "; book it in the ERP or reopen");
            }
        } catch (java.time.format.DateTimeParseException e) {
            // unparseable date: not a close violation
        }
    }

    private BigDecimal configValueOf(String key) {
        seedDefaults(tenantScope.currentTenantId());
        return mappings.findByTenantIdAndMappingKey(tenantScope.currentTenantId(), key)
                .map(AccountMapping::getConfigValue).map(v -> v == null ? BigDecimal.ZERO : v)
                .orElse(BigDecimal.ZERO);
    }

    /* ---------- reads ---------- */

    @Transactional(readOnly = true)
    public List<JournalEntryView> journal(LocalDate date) {
        return journal(date, null);
    }

    public List<JournalEntryView> journal(LocalDate date, String sourceRef) {
        String tenant = tenantScope.currentTenantId();
        // the proof run's lesson: an unfiltered list ages out of any fixed
        // page — asking about ONE source must be a repository question
        List<JournalEntry> found = sourceRef != null && !sourceRef.isBlank()
                ? entries.findByTenantIdAndSourceRef(tenant, sourceRef)
                : date == null
                ? entries.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)
                : entries.findAllByTenantIdAndEntryDateOrderByCreatedAtAsc(tenant, date);
        List<JournalEntryView> out = new ArrayList<>();
        for (JournalEntry e : found) {
            out.add(entryView(e, lines.findAllByTenantIdAndEntryIdOrderBySeqAsc(tenant, e.getId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public JournalEntryView entryById(String id) {
        String tenant = tenantScope.currentTenantId();
        JournalEntry e = entries.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("JournalEntry", id));
        return entryView(e, lines.findAllByTenantIdAndEntryIdOrderBySeqAsc(tenant, e.getId()));
    }

    /** CSV, one row per line — the shape a period-close import job wants.
     * format=sap|netsuite emits ERP-flavored column layouts (SHAPED to their
     * import conventions, not certified against a live instance — honest). */
    @Transactional(readOnly = true)
    public String exportCsv(LocalDate date, String format) {
        if ("sap".equalsIgnoreCase(format)) {
            StringBuilder sap = new StringBuilder("BLDAT,BUDAT,XBLNR,BKTXT,HKONT,SHKZG,WRBTR,WAERS\n");
            for (JournalEntryView entry : journal(date)) {
                for (JournalLineView l : entry.lines()) {
                    boolean debit = l.debit().signum() > 0;
                    sap.append(String.join(",", String.valueOf(entry.entryDate()),
                            String.valueOf(entry.entryDate()), String.valueOf(entry.sourceRef()),
                            quote(entry.description()), String.valueOf(l.accountCode()),
                            debit ? "S" : "H", plain(debit ? l.debit() : l.credit()),
                            String.valueOf(entry.currency()))).append('\n');
                }
            }
            return sap.toString();
        }
        if ("netsuite".equalsIgnoreCase(format)) {
            StringBuilder ns = new StringBuilder("Date,Journal,Account,Debit,Credit,Memo,Currency\n");
            for (JournalEntryView entry : journal(date)) {
                for (JournalLineView l : entry.lines()) {
                    ns.append(String.join(",", String.valueOf(entry.entryDate()),
                            String.valueOf(entry.id()),
                            quote(l.accountCode() + " " + l.accountName()),
                            plain(l.debit()), plain(l.credit()),
                            quote(l.description()), String.valueOf(entry.currency()))).append('\n');
                }
            }
            return ns.toString();
        }
        StringBuilder csv = new StringBuilder(
                "entryDate,entryId,sourceType,accountCode,accountName,debit,credit,currency,ref,description\n");
        for (JournalEntryView entry : journal(date)) {
            for (JournalLineView l : entry.lines()) {
                csv.append(String.join(",",
                        String.valueOf(entry.entryDate()), String.valueOf(entry.id()),
                        String.valueOf(entry.sourceType()), String.valueOf(l.accountCode()),
                        quote(l.accountName()), plain(l.debit()), plain(l.credit()),
                        String.valueOf(entry.currency()), quote(l.ref()),
                        quote(l.description()))).append('\n');
            }
        }
        return csv.toString();
    }

    /** The tie-out: per-account totals, AR vs cash, every entry balanced. */
    @Transactional(readOnly = true)
    public ReconciliationView reconciliation(LocalDate date) {
        List<JournalEntryView> day = journal(date);
        Map<String, AccountTotal> byAccount = new TreeMap<>();
        BigDecimal arDebits = BigDecimal.ZERO;
        BigDecimal cashDebits = BigDecimal.ZERO;
        BigDecimal bnplDebits = BigDecimal.ZERO;
        boolean allBalanced = true;
        for (JournalEntryView entry : day) {
            BigDecimal d = BigDecimal.ZERO;
            BigDecimal c = BigDecimal.ZERO;
            for (JournalLineView l : entry.lines()) {
                BigDecimal debit = money(l.debit());
                BigDecimal credit = money(l.credit());
                d = d.add(debit);
                c = c.add(credit);
                String code = String.valueOf(l.accountCode());
                byAccount.merge(code, new AccountTotal(code, l.accountName(), debit, credit),
                        (had, more) -> had.plus(more.debit(), more.credit()));
                String key = keyOfCode(code);
                if ("ar".equals(key)) {
                    arDebits = arDebits.add(debit);
                }
                if ("cash".equals(key)) {
                    cashDebits = cashDebits.add(debit);
                }
                if ("bnpl:receivable".equals(key)) {
                    // NET outstanding: captures debit it, remittances credit it back down
                    bnplDebits = bnplDebits.add(debit).subtract(credit);
                }
            }
            if (d.compareTo(c) != 0) {
                allBalanced = false;
            }
        }
        Long points = billingClient.loyaltyPointsLiability();
        ReconciliationView out = new ReconciliationView(
                date == null ? "all" : date.toString(), day.size(), allBalanced,
                arDebits, cashDebits,
                bnplDebits,   // captured but not yet remitted by the BNPL provider
                new ArrayList<>(byAccount.values()),
                points == null ? LoyaltyControl.unreachable() : LoyaltyControl.of(points),
                null, "RevenueReconciliation");
        PeriodClose close = periods.findById(tenantScope.currentTenantId()).orElse(null);
        return close == null ? out : out.closedThrough(close.getClosedThrough().toString());
    }

    /** REV-REC INPUTS: the obligation timeline the ERP's ASC 606 / IFRS 15
     * engine allocates over. One row per active commitment: who, what,
     * from-when, to-when, how many months, how far along. The BSS does
     * NOT restate prices here — the journal export already carries what
     * was billed; SSP allocation is deliberately the ERP's job. */
    @Transactional(readOnly = true)
    public List<RevRecRow> revrecInput() {
        LocalDate today = LocalDate.now();
        List<RevRecRow> out = new ArrayList<>();
        for (Map<String, Object> a : billingClient.agreements()) {
            if (!"active".equals(a.get("status"))) {
                continue;
            }
            Map<String, Object> period = castMap(a.get("agreementPeriod"));
            if (period.get("startDateTime") == null || period.get("endDateTime") == null) {
                continue;
            }
            LocalDate start = LocalDate.parse(String.valueOf(period.get("startDateTime")).substring(0, 10));
            LocalDate end = LocalDate.parse(String.valueOf(period.get("endDateTime")).substring(0, 10));
            long months = a.get("commitmentMonths") instanceof Number n ? n.longValue()
                    : java.time.temporal.ChronoUnit.MONTHS.between(start, end);
            long elapsed = Math.max(0, Math.min(months,
                    java.time.temporal.ChronoUnit.MONTHS.between(start, today)));
            String party = null;
            if (a.get("engagedParty") instanceof List<?> parties) {
                for (Object p2 : parties) {
                    Object refId = p2 instanceof Map<?, ?> ref ? ref.get("id") : null;
                    if (refId != null) {
                        party = refId.toString();
                        break;
                    }
                }
            }
            String offeringId = null;
            String offeringName = null;
            if (a.get("agreementItem") instanceof List<?> items && !items.isEmpty()
                    && items.get(0) instanceof Map<?, ?> item
                    && item.get("productOffering") instanceof Map<?, ?> po) {
                offeringId = str(po.get("id"));
                offeringName = str(po.get("name"));
            }
            out.add(new RevRecRow(str(a.get("id")), str(a.get("name")), party,
                    offeringId, offeringName, start.toString(), end.toString(),
                    months, elapsed, months - elapsed, "RevRecInput"));
        }
        return out;
    }

    /** The same timeline as the CSV a rev-rec import wants. */
    @Transactional(readOnly = true)
    public String revrecCsv() {
        StringBuilder csv = new StringBuilder(
                "contractId,contractName,partyId,offeringId,offeringName,startDate,endDate,commitmentMonths,monthsElapsed,monthsRemaining\n");
        for (RevRecRow row : revrecInput()) {
            csv.append(String.join(",", plain(row.contractId()), quote(row.contractName()),
                    plain(row.partyId()), plain(row.offeringId()),
                    quote(row.offeringName()), plain(row.startDate()),
                    plain(row.endDate()), plain(row.commitmentMonths()),
                    plain(row.monthsElapsed()), plain(row.monthsRemaining()))).append('\n');
        }
        return csv.toString();
    }

    /* ---------- the chart ---------- */

    @Transactional
    public List<ChartRow> chart() {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        List<ChartRow> out = new ArrayList<>();
        for (AccountMapping m : mappings.findAllByTenantIdOrderByMappingKeyAsc(tenant)) {
            out.add(new ChartRow(m.getMappingKey(), m.getAccountCode(), m.getAccountName(),
                    m.getConfigValue()));
        }
        return out;
    }

    @Transactional
    public RemapReceipt remap(RemapRequest dto) {
        String tenant = tenantScope.currentTenantId();
        String key = String.valueOf(dto.key());
        if (!DEFAULT_CHART.containsKey(key)) {
            throw new BadRequestException("unknown posting key '" + key + "' — one of " + DEFAULT_CHART.keySet());
        }
        if (dto.accountCode() == null || dto.accountName() == null) {
            throw new BadRequestException("accountCode and accountName are required");
        }
        seedDefaults(tenant);
        AccountMapping m = mappings.findByTenantIdAndMappingKey(tenant, key).orElseThrow();
        m.setAccountCode(dto.accountCode());
        m.setAccountName(dto.accountName());
        // absent leaves the setting alone; an explicit JSON null clears it
        if (dto.configValue() != null) {
            m.setConfigValue(dto.configValue().isNull() ? null
                    : new BigDecimal(dto.configValue().asText()));
        }
        mappings.save(m);
        return RemapReceipt.of(key, m.getAccountCode(), m.getAccountName(), m.getConfigValue());
    }

    /* ---------- internals ---------- */

    private void seedDefaults(String tenant) {
        for (Map.Entry<String, String[]> def : DEFAULT_CHART.entrySet()) {
            if (mappings.findByTenantIdAndMappingKey(tenant, def.getKey()).isEmpty()) {
                AccountMapping m = new AccountMapping();
                m.setTenantId(tenant);
                m.setMappingKey(def.getKey());
                m.setAccountCode(def.getValue()[0]);
                m.setAccountName(def.getValue()[1]);
                mappings.save(m);
            }
        }
    }

    /* ---------- reporting: governed period summary from the subledger ---------- */

    /**
     * A governed sales/finance summary for [from, to], computed once from the
     * subledger (the source of truth) — never re-derived. Net revenue is
     * credit-minus-debit over the revenue-family accounts (so discounts, credit
     * notes, disputes and refunds net down exactly as booked); tax and cash come
     * from their own accounts; and the prior equal-length period gives the delta.
     */
    @Transactional(readOnly = true)
    public SummaryView summary(LocalDate from, LocalDate to) {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        String taxCode = codeFor(tenant, "tax");
        String cashCode = codeFor(tenant, "cash");
        // everything that is NOT one of these is revenue-family (4xxx + contra)
        Set<String> nonRevenue = Set.of(cashCode, codeFor(tenant, "ar"), taxCode,
                codeFor(tenant, "bnpl:receivable"),
                codeFor(tenant, "loyalty:expense"), codeFor(tenant, "loyalty:liability"));

        Totals cur = totals(tenant, from, to, nonRevenue, taxCode, cashCode);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(days - 1);
        Totals prev = totals(tenant, priorFrom, priorTo, nonRevenue, taxCode, cashCode);

        long invoices = entries.countByTenantIdAndSourceTypeAndEntryDateBetween(tenant, "bill", from, to);

        return new SummaryView("RevenueSummary",
                new Period(from.toString(), to.toString()),
                cur.revenue(), cur.tax(), cur.cash(), invoices, prev.revenue(),
                pctDelta(prev.revenue(), cur.revenue()), cur.byAccount());
    }

    /**
     * The subscription-metrics engine (console P3): an MRR waterfall computed
     * from the subledger's OWN rows — account 4000 (recurring service revenue)
     * credited per customer per month. Billed truth, not catalog list price:
     * a month shows what the billing run actually recognised. Per month, each
     * customer is classified against the PRIOR month — absent→present = NEW,
     * up = EXPANSION, down = CONTRACTION, present→absent = CHURNED — so the
     * waterfall identity holds by construction:
     * mrr(m) = mrr(m-1) + new + expansion − contraction − churn.
     * The FIRST month in range has no prior, so it is the baseline (all NEW).
     */
    @Transactional(readOnly = true)
    public SubscriptionMetricsView subscriptionMetrics(LocalDate from, LocalDate to) {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        String code = codeFor(tenant, "rate:recurringCharge");   // 4000 unless remapped
        // pull one prior month so the first requested month classifies properly
        LocalDate pullFrom = from.withDayOfMonth(1).minusMonths(1);
        Map<String, Map<String, BigDecimal>> byMonth = new TreeMap<>();
        for (Object[] row : lines.monthlyNetByParty(tenant, code, pullFrom, to)) {
            String month = String.format("%04d-%02d", ((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            String party = row[2] == null ? "(unattributed)" : String.valueOf(row[2]);
            BigDecimal net = bd(row[3]).subtract(bd(row[4]));   // credit − debit
            if (net.signum() != 0) {
                byMonth.computeIfAbsent(month, k -> new LinkedHashMap<>()).merge(party, net, BigDecimal::add);
            }
        }
        // walk the REQUESTED months in order, classifying against the prior month
        List<MonthRow> months = new ArrayList<>();
        List<DrillRow> drill = new ArrayList<>();
        String firstMonth = String.format("%04d-%02d", from.getYear(), from.getMonthValue());
        String lastMonth = String.format("%04d-%02d", to.getYear(), to.getMonthValue());
        BigDecimal prevMrr = null;
        for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
            String month = String.format("%04d-%02d", m.getYear(), m.getMonthValue());
            Map<String, BigDecimal> cur = byMonth.getOrDefault(month, Map.of());
            String prior = String.format("%04d-%02d", m.minusMonths(1).getYear(), m.minusMonths(1).getMonthValue());
            Map<String, BigDecimal> prev = byMonth.getOrDefault(prior, Map.of());
            BigDecimal mrr = BigDecimal.ZERO;
            BigDecimal newMrr = BigDecimal.ZERO;
            BigDecimal expansion = BigDecimal.ZERO;
            BigDecimal contraction = BigDecimal.ZERO;
            BigDecimal churned = BigDecimal.ZERO;
            int churnedAccounts = 0;
            for (Map.Entry<String, BigDecimal> e : cur.entrySet()) {
                mrr = mrr.add(e.getValue());
                BigDecimal was = prev.get(e.getKey());
                String kind;
                BigDecimal delta;
                if (was == null) {
                    kind = "new";
                    delta = e.getValue();
                    newMrr = newMrr.add(delta);
                } else if (e.getValue().compareTo(was) > 0) {
                    kind = "expansion";
                    delta = e.getValue().subtract(was);
                    expansion = expansion.add(delta);
                } else if (e.getValue().compareTo(was) < 0) {
                    kind = "contraction";
                    delta = was.subtract(e.getValue());
                    contraction = contraction.add(delta);
                } else {
                    kind = "flat";
                    delta = BigDecimal.ZERO;
                }
                if (month.equals(lastMonth) && !"flat".equals(kind)) {
                    drill.add(drillRow(month, e.getKey(), kind, delta, e.getValue()));
                }
            }
            for (Map.Entry<String, BigDecimal> e : prev.entrySet()) {
                if (!cur.containsKey(e.getKey())) {
                    churned = churned.add(e.getValue());
                    churnedAccounts++;
                    if (month.equals(lastMonth)) {
                        drill.add(drillRow(month, e.getKey(), "churned", e.getValue(), BigDecimal.ZERO));
                    }
                }
            }
            int active = cur.size();
            // NRR: what last month's customers are worth now / what they were worth
            BigDecimal prevTotal = prev.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            months.add(new MonthRow(month, scale(mrr), scale(newMrr), scale(expansion),
                    scale(contraction), scale(churned), active,
                    active == 0 ? BigDecimal.ZERO
                            : scale(mrr.divide(BigDecimal.valueOf(active), 2, RoundingMode.HALF_UP)),
                    prev.isEmpty() ? null
                            : scale(BigDecimal.valueOf(churnedAccounts * 100.0 / prev.size())),
                    prevTotal.signum() == 0 ? null
                            : scale(mrr.subtract(newMrr).multiply(BigDecimal.valueOf(100))
                                    .divide(prevTotal, 1, RoundingMode.HALF_UP)),
                    month.equals(firstMonth) && prev.isEmpty()));
            prevMrr = mrr;
        }
        drill.sort((a, b) -> b.delta().compareTo(a.delta()));
        return new SubscriptionMetricsView(new Period(from.toString(), to.toString()), code,
                "billed recurring revenue from the subledger (account " + code
                        + "); a month reflects what the billing run recognised, not catalog list price",
                months, drill.size() > 100 ? drill.subList(0, 100) : drill, "SubscriptionMetrics");
    }

    private static DrillRow drillRow(String month, String party, String kind,
            BigDecimal delta, BigDecimal nowMrr) {
        return new DrillRow(month, party, kind, scale(delta), scale(nowMrr));
    }

    /** The waterfall as CSV — one row per month, the drill-down appended. */
    @Transactional(readOnly = true)
    public String subscriptionMetricsCsv(LocalDate from, LocalDate to) {
        SubscriptionMetricsView m = subscriptionMetrics(from, to);
        StringBuilder csv = new StringBuilder(
                "month,mrr,newMrr,expansionMrr,contractionMrr,churnedMrr,activeAccounts,arpu,churnRatePct,nrrPct\n");
        for (MonthRow r : m.months()) {
            csv.append(r.month()).append(',').append(r.mrr()).append(',')
                    .append(r.newMrr()).append(',').append(r.expansionMrr()).append(',')
                    .append(r.contractionMrr()).append(',').append(r.churnedMrr()).append(',')
                    .append(r.activeAccounts()).append(',').append(r.arpu()).append(',')
                    .append(r.churnRatePct() == null ? "" : r.churnRatePct()).append(',')
                    .append(r.nrrPct() == null ? "" : r.nrrPct()).append('\n');
        }
        csv.append("\nmonth,partyId,kind,delta,mrr\n");
        for (DrillRow r : m.drillDown()) {
            csv.append(r.month()).append(',').append(r.partyId()).append(',')
                    .append(r.kind()).append(',').append(r.delta()).append(',')
                    .append(r.mrr()).append('\n');
        }
        return csv.toString();
    }

    private Totals totals(String tenant, LocalDate from, LocalDate to,
            Set<String> nonRevenue, String taxCode, String cashCode) {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        List<AccountNet> byAccount = new ArrayList<>();
        for (Object[] row : lines.sumByAccountBetween(tenant, from, to)) {
            String code = (String) row[0];
            String name = (String) row[1];
            BigDecimal debit = bd(row[2]);
            BigDecimal credit = bd(row[3]);
            if (code.equals(taxCode)) {
                tax = tax.add(credit.subtract(debit));
            } else if (code.equals(cashCode)) {
                cash = cash.add(debit.subtract(credit));           // cash inflow
            } else if (!nonRevenue.contains(code)) {
                BigDecimal net = credit.subtract(debit);            // revenue up, contra down
                revenue = revenue.add(net);
                byAccount.add(new AccountNet(code, name, scale(net)));
            }
        }
        byAccount.sort((a, b) -> b.net().compareTo(a.net()));
        return new Totals(scale(revenue), scale(tax), scale(cash), byAccount);
    }

    private record Totals(BigDecimal revenue, BigDecimal tax, BigDecimal cash,
            List<AccountNet> byAccount) {
    }

    private String codeFor(String tenant, String key) {
        return mappings.findByTenantIdAndMappingKey(tenant, key)
                .map(AccountMapping::getAccountCode)
                .orElseGet(() -> DEFAULT_CHART.get(key)[0]);
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    /** Percent change vs the prior period, or null when there's no base to compare. */
    private static BigDecimal pctDelta(BigDecimal prev, BigDecimal cur) {
        if (prev == null || prev.signum() == 0) {
            return null;
        }
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100))
                .divide(prev.abs(), 1, RoundingMode.HALF_UP);
    }

    private JournalLine line(String key, BigDecimal debit, BigDecimal credit, String ref, String description) {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        AccountMapping account = mappings.findByTenantIdAndMappingKey(tenant, key).orElseThrow();
        JournalLine l = new JournalLine();
        l.setId(UUID.randomUUID().toString());
        l.setTenantId(tenant);
        l.setAccountCode(account.getAccountCode());
        l.setAccountName(account.getAccountName());
        l.setDebit(debit == null ? BigDecimal.ZERO : scale(debit));
        l.setCredit(credit == null ? BigDecimal.ZERO : scale(credit));
        l.setRef(ref);
        l.setDescription(description);
        return l;
    }

    private void saveBalanced(String tenant, String sourceRef, String sourceType, String description,
            String currency, String party, List<JournalLine> posting) {
        BigDecimal debits = posting.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = posting.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new BadRequestException("unbalanced posting for " + sourceRef + ": debits " + debits
                    + " != credits " + credits + " — refusing to book");
        }
        JournalEntry e = new JournalEntry();
        e.setId(UUID.randomUUID().toString());
        e.setTenantId(tenant);
        e.setEntryDate(LocalDate.now());
        e.setSourceRef(sourceRef);
        e.setSourceType(sourceType);
        e.setDescription(description);
        e.setCurrency(currency);
        e.setPartyId(party);
        e.setCreatedAt(OffsetDateTime.now());
        entries.save(e);
        int seq = 0;
        for (JournalLine l : posting) {
            l.setEntryId(e.getId());
            l.setSeq(seq++);
            lines.save(l);
        }
        log.info("revenue: booked {} ({} lines, {} {})", sourceRef, posting.size(), debits, currency);
    }

    private JournalEntryView entryView(JournalEntry e, List<JournalLine> entryLines) {
        List<JournalLineView> ls = new ArrayList<>();
        for (JournalLine l : entryLines) {
            ls.add(new JournalLineView(l.getSeq(), l.getAccountCode(), l.getAccountName(),
                    l.getDebit(), l.getCredit(), l.getRef(), l.getDescription()));
        }
        return new JournalEntryView(e.getId(), e.getEntryDate(), e.getSourceRef(), e.getSourceType(),
                e.getDescription(), e.getCurrency(),
                e.getPartyId() == null ? null : List.of(PartyRef.customer(e.getPartyId())),
                ls, "JournalEntry");
    }

    private String keyOfCode(String code) {
        String tenant = tenantScope.currentTenantId();
        for (AccountMapping m : mappings.findAllByTenantIdOrderByMappingKeyAsc(tenant)) {
            if (m.getAccountCode().equals(code)) {
                return m.getMappingKey();
            }
        }
        return null;
    }

    /** The payment's own owner if it names one, else the related party. */
    private static String ownerOf(Map<String, Object> payment) {
        Object owner = payment.get("ownerPartyId");
        return owner == null ? partyOf(payment) : owner.toString();
    }

    private static String partyOf(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                Object refId = p instanceof Map<?, ?> ref ? ref.get("id") : null;
                if (refId != null) {
                    return refId.toString();
                }
            }
        }
        return null;
    }

    private static BigDecimal money(Object value) {
        try {
            return value == null ? BigDecimal.ZERO : scale(new BigDecimal(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object m) {
        return m instanceof Map ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** A foreign document from billing: read as a tree, converted once at the boundary. */
    private Map<String, Object> asMap(JsonNode node) {
        return node == null || !node.isObject() ? new LinkedHashMap<>() : json.convertValue(node, OPEN_MAP);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String plain(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String quote(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
