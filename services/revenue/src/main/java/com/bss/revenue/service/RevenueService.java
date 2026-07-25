package com.bss.revenue.service;

import com.bss.revenue.client.BillingClient;
import com.bss.revenue.entity.AccountMapping;
import com.bss.revenue.entity.JournalEntry;
import com.bss.revenue.entity.JournalLine;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Map<String, String[]> DEFAULT_CHART = Map.of(
            "ar", new String[] {"1200", "Accounts receivable"},
            "cash", new String[] {"1000", "Cash / PSP clearing"},
            "rate:recurringCharge", new String[] {"4000", "Service revenue"},
            "rate:usageCharge", new String[] {"4010", "Usage revenue"},
            "rate:discount", new String[] {"4090", "Discounts (contra-revenue)"},
            "rate:priceAdjustment", new String[] {"4091", "Pricing adjustments"},
            "refund", new String[] {"4095", "Refunds (contra-revenue)"});

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final AccountMappingRepository mappings;
    private final BillingClient billingClient;
    private final TenantScope tenantScope;

    public RevenueService(JournalEntryRepository entries, JournalLineRepository lines,
            AccountMappingRepository mappings, BillingClient billingClient, TenantScope tenantScope) {
        this.entries = entries;
        this.lines = lines;
        this.mappings = mappings;
        this.billingClient = billingClient;
        this.tenantScope = tenantScope;
    }

    /* ---------- posting builders ---------- */

    /** Invoice issued: debit AR for the bill total, credit revenue per line. */
    @Transactional
    public boolean postBill(String billId, Map<String, Object> billEvent) {
        String tenant = tenantScope.currentTenantId();
        String sourceRef = "bill:" + billId;
        if (entries.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return false;
        }
        List<Map<String, Object>> rates = billingClient.ratesOf(billId);
        if (rates == null || rates.isEmpty()) {
            throw new BadRequestException("bill " + billId + " has no rate lines yet");
        }
        Map<String, Object> amountDue = billEvent.get("amountDue") instanceof Map<?, ?> m
                ? castMap(m) : castMap(billingClient.bill(billId).get("amountDue"));
        BigDecimal total = money(amountDue.get("value"));
        String currency = amountDue.get("unit") == null ? "EUR" : String.valueOf(amountDue.get("unit"));
        String party = partyOf(billEvent);

        List<JournalLine> posting = new ArrayList<>();
        posting.add(line("ar", total, null, billId, "Invoice " + billEvent.getOrDefault("billNo", billId)));
        for (Map<String, Object> rate : rates) {
            String type = String.valueOf(rate.getOrDefault("type", "recurringCharge"));
            BigDecimal amount = money(rate.get("taxExcludedAmount") instanceof Map<?, ?> a
                    ? castMap(a).get("value") : null);
            if (amount.signum() == 0) {
                continue;
            }
            // discounts arrive NEGATIVE: a negative credit is a debit to contra-revenue
            String key = DEFAULT_CHART.containsKey("rate:" + type) ? "rate:" + type : "rate:priceAdjustment";
            posting.add(amount.signum() < 0
                    ? line(key, amount.negate(), null, billId, String.valueOf(rate.get("name")))
                    : line(key, null, amount, billId, String.valueOf(rate.get("name"))));
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
        List<JournalLine> posting = List.of(
                line("cash", value, null, paymentId, "Payment " + paymentId),
                line("ar", null, value, paymentId, "Payment applied"));
        saveBalanced(tenant, sourceRef, "payment", "Cash received — " + paymentId, currency,
                payment.get("ownerPartyId") == null ? partyOf(payment) : String.valueOf(payment.get("ownerPartyId")),
                posting);
        return true;
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
    public Map<String, Object> backfill(String billId) {
        Map<String, Object> bill = billingClient.bill(billId);
        boolean posted = postBill(billId, bill);
        return Map.of("billId", billId, "posted", posted,
                "note", posted ? "journal entry created" : "already journaled — nothing to do");
    }

    /* ---------- reads ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> journal(LocalDate date) {
        String tenant = tenantScope.currentTenantId();
        List<JournalEntry> found = date == null
                ? entries.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)
                : entries.findAllByTenantIdAndEntryDateOrderByCreatedAtAsc(tenant, date);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JournalEntry e : found) {
            out.add(entryView(e, lines.findAllByTenantIdAndEntryIdOrderBySeqAsc(tenant, e.getId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> entryById(String id) {
        String tenant = tenantScope.currentTenantId();
        JournalEntry e = entries.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("JournalEntry", id));
        return entryView(e, lines.findAllByTenantIdAndEntryIdOrderBySeqAsc(tenant, e.getId()));
    }

    /** CSV, one row per line — the shape a period-close import job wants. */
    @Transactional(readOnly = true)
    public String exportCsv(LocalDate date) {
        StringBuilder csv = new StringBuilder(
                "entryDate,entryId,sourceType,accountCode,accountName,debit,credit,currency,ref,description\n");
        for (Map<String, Object> entry : journal(date)) {
            for (Object o : (List<?>) entry.get("lines")) {
                Map<String, Object> l = castMap(o);
                csv.append(String.join(",",
                        String.valueOf(entry.get("entryDate")), String.valueOf(entry.get("id")),
                        String.valueOf(entry.get("sourceType")), String.valueOf(l.get("accountCode")),
                        quote(l.get("accountName")), plain(l.get("debit")), plain(l.get("credit")),
                        String.valueOf(entry.get("currency")), quote(l.get("ref")),
                        quote(l.get("description")))).append('\n');
            }
        }
        return csv.toString();
    }

    /** The tie-out: per-account totals, AR vs cash, every entry balanced. */
    @Transactional(readOnly = true)
    public Map<String, Object> reconciliation(LocalDate date) {
        List<Map<String, Object>> day = journal(date);
        Map<String, Map<String, Object>> byAccount = new TreeMap<>();
        BigDecimal arDebits = BigDecimal.ZERO;
        BigDecimal cashDebits = BigDecimal.ZERO;
        boolean allBalanced = true;
        for (Map<String, Object> entry : day) {
            BigDecimal d = BigDecimal.ZERO;
            BigDecimal c = BigDecimal.ZERO;
            for (Object o : (List<?>) entry.get("lines")) {
                Map<String, Object> l = castMap(o);
                BigDecimal debit = money(l.get("debit"));
                BigDecimal credit = money(l.get("credit"));
                d = d.add(debit);
                c = c.add(credit);
                String code = String.valueOf(l.get("accountCode"));
                Map<String, Object> acc = byAccount.computeIfAbsent(code, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountCode", k);
                    m.put("accountName", l.get("accountName"));
                    m.put("debit", BigDecimal.ZERO);
                    m.put("credit", BigDecimal.ZERO);
                    return m;
                });
                acc.put("debit", ((BigDecimal) acc.get("debit")).add(debit));
                acc.put("credit", ((BigDecimal) acc.get("credit")).add(credit));
                String key = keyOfCode(code);
                if ("ar".equals(key)) {
                    arDebits = arDebits.add(debit);
                }
                if ("cash".equals(key)) {
                    cashDebits = cashDebits.add(debit);
                }
            }
            if (d.compareTo(c) != 0) {
                allBalanced = false;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date == null ? "all" : date.toString());
        out.put("entries", day.size());
        out.put("allEntriesBalanced", allBalanced);
        out.put("billedTotal", arDebits);
        out.put("cashTotal", cashDebits);
        out.put("byAccount", new ArrayList<>(byAccount.values()));
        Long points = billingClient.loyaltyPointsLiability();
        out.put("loyaltyPointsLiability", points == null
                ? Map.of("note", "no loyalty component reachable")
                : Map.of("points", points, "note",
                        "control number — no currency valuation configured (see plan P2)"));
        out.put("@type", "RevenueReconciliation");
        return out;
    }

    /* ---------- the chart ---------- */

    @Transactional
    public List<Map<String, Object>> chart() {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccountMapping m : mappings.findAllByTenantIdOrderByMappingKeyAsc(tenant)) {
            out.add(Map.of("key", m.getMappingKey(), "accountCode", m.getAccountCode(),
                    "accountName", m.getAccountName()));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> remap(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String key = String.valueOf(dto.get("key"));
        if (!DEFAULT_CHART.containsKey(key)) {
            throw new BadRequestException("unknown posting key '" + key + "' — one of " + DEFAULT_CHART.keySet());
        }
        if (dto.get("accountCode") == null || dto.get("accountName") == null) {
            throw new BadRequestException("accountCode and accountName are required");
        }
        seedDefaults(tenant);
        AccountMapping m = mappings.findByTenantIdAndMappingKey(tenant, key).orElseThrow();
        m.setAccountCode(String.valueOf(dto.get("accountCode")));
        m.setAccountName(String.valueOf(dto.get("accountName")));
        mappings.save(m);
        return Map.of("key", key, "accountCode", m.getAccountCode(), "accountName", m.getAccountName(),
                "note", "applies to FUTURE postings — booked lines keep their snapshot");
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

    private Map<String, Object> entryView(JournalEntry e, List<JournalLine> entryLines) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("entryDate", e.getEntryDate());
        out.put("sourceRef", e.getSourceRef());
        out.put("sourceType", e.getSourceType());
        out.put("description", e.getDescription());
        out.put("currency", e.getCurrency());
        if (e.getPartyId() != null) {
            out.put("relatedParty", List.of(Map.of("id", e.getPartyId(), "role", "customer")));
        }
        List<Map<String, Object>> ls = new ArrayList<>();
        for (JournalLine l : entryLines) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("seq", l.getSeq());
            lm.put("accountCode", l.getAccountCode());
            lm.put("accountName", l.getAccountName());
            lm.put("debit", l.getDebit());
            lm.put("credit", l.getCredit());
            lm.put("ref", l.getRef());
            lm.put("description", l.getDescription());
            ls.add(lm);
        }
        out.put("lines", ls);
        out.put("@type", "JournalEntry");
        return out;
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

    private static String partyOf(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("id") != null) {
                    return String.valueOf(ref.get("id"));
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

    private static String plain(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String quote(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
