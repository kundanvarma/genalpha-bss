package com.bss.usage.service;

import com.bss.usage.entity.ImsiRange;
import com.bss.usage.entity.UsageRecord;
import com.bss.usage.entity.WholesaleRateCard;
import com.bss.usage.entity.WholesaleUsageLedger;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.repository.ImsiRangeRepository;
import com.bss.usage.repository.UsageRecordRepository;
import com.bss.usage.repository.WholesaleRateCardRepository;
import com.bss.usage.repository.WholesaleUsageLedgerRepository;
import com.bss.usage.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mobile wholesale (MVNE), the seeker side: an MVNO (this tenant) owes its host
 * MNO for the traffic its subscribers burn. A second rating pass over the same
 * CDRs — retail rating is untouched — at the host's wholesale rate card, into a
 * per-period ledger. Usage-metered, the mobile sibling of the fibre per-line
 * settlement.
 */
@Service
public class WholesaleUsageService {

    private final UsageRecordRepository records;
    private final WholesaleRateCardRepository rateCards;
    private final WholesaleUsageLedgerRepository ledger;
    private final ImsiRangeRepository imsiRanges;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public WholesaleUsageService(UsageRecordRepository records, WholesaleRateCardRepository rateCards,
            WholesaleUsageLedgerRepository ledger, ImsiRangeRepository imsiRanges,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.records = records;
        this.rateCards = rateCards;
        this.ledger = ledger;
        this.imsiRanges = imsiRanges;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    private OffsetDateTime start(LocalDate d) { return d.atStartOfDay().atOffset(ZoneOffset.UTC); }
    private OffsetDateTime endExclusive(LocalDate d) { return d.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC); }

    private Map<String, BigDecimal> unitsBySpec(String tenant, LocalDate periodStart, LocalDate periodEnd) {
        List<UsageRecord> recs = records.findByTenantIdAndUsageDateBetween(
                tenant, start(periodStart), endExclusive(periodEnd));
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        for (UsageRecord r : recs) {
            if (r.getUsageSpecName() == null || r.getValue() == null) {
                continue;
            }
            units.merge(r.getUsageSpecName(), r.getValue(), BigDecimal::add);
        }
        return units;
    }

    /**
     * Rate the MVNO's CDRs for the period at wholesale rates into the ledger.
     * Idempotent: one ledger row per (tenant, period, usage type) — an existing
     * row is returned untouched, so re-running never double-books and revenue
     * (keyed on the ledger id) never double-posts COGS.
     */
    @Transactional
    public List<Map<String, Object>> rateWholesale(LocalDate periodStart, LocalDate periodEnd) {
        String tenant = tenantScope.currentTenantId();
        Map<String, BigDecimal> units = unitsBySpec(tenant, periodStart, periodEnd);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : units.entrySet()) {
            String spec = e.getKey();
            Optional<WholesaleRateCard> cardOpt = rateCards.findByTenantIdAndUsageSpecName(tenant, spec);
            if (cardOpt.isEmpty()) {
                continue;   // no wholesale rate agreed for this usage type — skip honestly
            }
            WholesaleRateCard card = cardOpt.get();
            Optional<WholesaleUsageLedger> existing =
                    ledger.findByTenantIdAndPeriodStartAndUsageSpecName(tenant, periodStart, spec);
            if (existing.isPresent()) {
                out.add(ledgerMap(existing.get()));
                continue;
            }
            WholesaleUsageLedger row = new WholesaleUsageLedger();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setPeriodStart(periodStart);
            row.setUsageSpecName(spec);
            row.setTotalUnits(e.getValue());
            row.setUnit(card.getUnit());
            row.setWholesaleRate(card.getWholesaleRate());
            row.setAmount(e.getValue().multiply(card.getWholesaleRate()).setScale(2, RoundingMode.HALF_UP));
            row.setCurrency(card.getCurrency() == null ? "EUR" : card.getCurrency());
            row.setHostPartyId(card.getHostPartyId());
            row.setStatus("rated");
            row.setCreatedAt(OffsetDateTime.now());
            ledger.save(row);
            // Revenue books the MVNO's wholesale cost off this event (amount rides it).
            events.publish("WholesaleUsageRatedEvent", "wholesaleUsageLedger", ledgerMap(row));
            out.add(ledgerMap(row));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> ledgerFor(LocalDate periodStart) {
        return ledger.findByTenantIdAndPeriodStart(tenantScope.currentTenantId(), periodStart)
                .stream().map(this::ledgerMap).toList();
    }

    /**
     * The settlement statement + a reconciliation view: the units the ledger was
     * rated on vs the units the CDRs show NOW. A mismatch (a CDR that landed after
     * rating) is flagged — revenue assurance, not just a sum.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> settlement(LocalDate periodStart, LocalDate periodEnd) {
        String tenant = tenantScope.currentTenantId();
        List<WholesaleUsageLedger> rows = ledger.findByTenantIdAndPeriodStart(tenant, periodStart);
        Map<String, BigDecimal> live = unitsBySpec(tenant, periodStart, periodEnd);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean reconciled = true;
        String host = null;
        for (WholesaleUsageLedger row : rows) {
            BigDecimal liveUnits = live.getOrDefault(row.getUsageSpecName(), BigDecimal.ZERO);
            boolean match = liveUnits.compareTo(row.getTotalUnits()) == 0;
            if (!match) {
                reconciled = false;
            }
            host = row.getHostPartyId();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("usageSpecName", row.getUsageSpecName());
            line.put("ratedUnits", row.getTotalUnits());
            line.put("liveUnits", liveUnits);
            line.put("unit", row.getUnit());
            line.put("wholesaleRate", row.getWholesaleRate());
            line.put("amount", row.getAmount());
            line.put("currency", row.getCurrency());
            line.put("reconciled", match);
            lines.add(line);
            total = total.add(row.getAmount());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "MobileWholesaleSettlement");
        out.put("periodType", "month");
        out.put("periodStart", periodStart.toString());
        out.put("hostPartyId", host);
        out.put("line", lines);
        out.put("totalOwed", total.setScale(2, RoundingMode.HALF_UP));
        out.put("currency", "EUR");
        out.put("reconciled", reconciled);
        return out;
    }

    /* ---------- rate card + IMSI admin ---------- */

    @Transactional
    public Map<String, Object> upsertRateCard(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String spec = String.valueOf(dto.get("usageSpecName"));
        WholesaleRateCard card = rateCards.findByTenantIdAndUsageSpecName(tenant, spec)
                .orElseGet(WholesaleRateCard::new);
        if (card.getId() == null) {
            card.setId(UUID.randomUUID().toString());
            card.setTenantId(tenant);
            card.setUsageSpecName(spec);
        }
        card.setWholesaleRate(new BigDecimal(String.valueOf(dto.get("wholesaleRate"))));
        card.setUnit(dto.get("unit") == null ? null : String.valueOf(dto.get("unit")));
        card.setCurrency(dto.get("currency") == null ? "EUR" : String.valueOf(dto.get("currency")));
        card.setHostPartyId(dto.get("hostPartyId") == null ? null : String.valueOf(dto.get("hostPartyId")));
        card.setHostName(dto.get("hostName") == null ? null : String.valueOf(dto.get("hostName")));
        card.setLastUpdate(OffsetDateTime.now());
        return rateCardMap(rateCards.save(card));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rateCards() {
        return rateCards.findByTenantId(tenantScope.currentTenantId()).stream().map(this::rateCardMap).toList();
    }

    @Transactional
    public Map<String, Object> allocateImsi(Map<String, Object> dto) {
        ImsiRange r = new ImsiRange();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setHostPartyId(dto.get("hostPartyId") == null ? null : String.valueOf(dto.get("hostPartyId")));
        r.setHostName(dto.get("hostName") == null ? null : String.valueOf(dto.get("hostName")));
        r.setPrefix(dto.get("prefix") == null ? null : String.valueOf(dto.get("prefix")));
        r.setFromImsi(String.valueOf(dto.get("fromImsi")));
        r.setToImsi(String.valueOf(dto.get("toImsi")));
        r.setCapacity(dto.get("capacity") == null ? null : Integer.valueOf(String.valueOf(dto.get("capacity"))));
        r.setNote(dto.get("note") == null ? null : String.valueOf(dto.get("note")));
        r.setAllocatedAt(OffsetDateTime.now());
        return imsiMap(imsiRanges.save(r));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> imsiRanges() {
        return imsiRanges.findByTenantId(tenantScope.currentTenantId()).stream().map(this::imsiMap).toList();
    }

    /* ---------- mappers ---------- */

    private Map<String, Object> ledgerMap(WholesaleUsageLedger r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("periodStart", r.getPeriodStart() == null ? null : r.getPeriodStart().toString());
        m.put("usageSpecName", r.getUsageSpecName());
        m.put("totalUnits", r.getTotalUnits());
        m.put("unit", r.getUnit());
        m.put("wholesaleRate", r.getWholesaleRate());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("hostPartyId", r.getHostPartyId());
        m.put("status", r.getStatus());
        m.put("@type", "WholesaleUsageLedger");
        return m;
    }

    private Map<String, Object> rateCardMap(WholesaleRateCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("usageSpecName", c.getUsageSpecName());
        m.put("wholesaleRate", c.getWholesaleRate());
        m.put("unit", c.getUnit());
        m.put("currency", c.getCurrency());
        m.put("hostPartyId", c.getHostPartyId());
        m.put("hostName", c.getHostName());
        m.put("@type", "WholesaleRateCard");
        return m;
    }

    private Map<String, Object> imsiMap(ImsiRange r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("hostPartyId", r.getHostPartyId());
        m.put("hostName", r.getHostName());
        m.put("prefix", r.getPrefix());
        m.put("fromImsi", r.getFromImsi());
        m.put("toImsi", r.getToImsi());
        m.put("capacity", r.getCapacity());
        m.put("note", r.getNote());
        m.put("@type", "ImsiRange");
        return m;
    }
}
