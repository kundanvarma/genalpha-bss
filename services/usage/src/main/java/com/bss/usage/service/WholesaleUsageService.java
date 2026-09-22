package com.bss.usage.service;

import com.bss.usage.dto.ImsiRangeRequest;
import com.bss.usage.dto.ImsiRangeView;
import com.bss.usage.dto.SimulateWholesaleRequest;
import com.bss.usage.dto.WholesaleLedgerView;
import com.bss.usage.dto.WholesaleRateCardRequest;
import com.bss.usage.dto.WholesaleRateCardView;
import com.bss.usage.dto.WholesaleRerated;
import com.bss.usage.dto.WholesaleSettlement;
import com.bss.usage.dto.WholesaleSimulation;
import com.bss.usage.entity.ImsiRange;
import com.bss.usage.exception.BadRequestException;
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
    private final TenantClock clock;

    public WholesaleUsageService(UsageRecordRepository records, WholesaleRateCardRepository rateCards,
            WholesaleUsageLedgerRepository ledger, ImsiRangeRepository imsiRanges,
            DomainEventPublisher events, TenantScope tenantScope,
            TenantClock clock) {
        this.clock = clock;
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
    public List<WholesaleLedgerView> rateWholesale(LocalDate periodStart, LocalDate periodEnd) {
        String tenant = tenantScope.currentTenantId();
        Map<String, BigDecimal> units = unitsBySpec(tenant, periodStart, periodEnd);
        List<WholesaleLedgerView> out = new ArrayList<>();
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
                out.add(ledgerView(existing.get()));
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
            events.publish("WholesaleUsageRatedEvent", "wholesaleUsageLedger", ledgerView(row));
            out.add(ledgerView(row));
        }
        return out;
    }

    /**
     * THE CLOSED LOOP (late-CDR auto re-rating): reconciliation used to only
     * FLAG a ledger row whose CDRs kept arriving after rating — a human had to
     * notice and fix. The loop now re-rates the drifted row (monthly periods),
     * stamps the receipt (rerateCount, lastReratedAt) and emits
     * WholesaleUsageReratedEvent carrying the DELTA so the revenue subledger
     * books an adjustment, idempotent per (ledger id, rerateCount). Bounded by
     * a window: a settled old period is a dispute, not a silent mutation.
     */
    @Transactional
    public List<WholesaleRerated> rerateDrifted(String tenant, int windowDays) {
        LocalDate earliest = clock.today().minusDays(windowDays).withDayOfMonth(1);   // T3: the window lives on the tenant clock
        List<WholesaleRerated> rerated = new ArrayList<>();
        for (WholesaleUsageLedger row : ledger.findByTenantIdAndPeriodStartGreaterThanEqual(tenant, earliest)) {
            LocalDate periodStart = row.getPeriodStart();
            LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);
            BigDecimal live = unitsBySpec(tenant, periodStart, periodEnd)
                    .getOrDefault(row.getUsageSpecName(), BigDecimal.ZERO);
            if (live.signum() == 0 || live.compareTo(row.getTotalUnits()) == 0) {
                continue;
            }
            BigDecimal oldAmount = row.getAmount();
            row.setTotalUnits(live);
            row.setAmount(live.multiply(row.getWholesaleRate()).setScale(2, RoundingMode.HALF_UP));
            row.setRerateCount(row.getRerateCount() + 1);
            row.setLastReratedAt(OffsetDateTime.now());
            ledger.save(row);
            WholesaleRerated event = new WholesaleRerated(ledgerView(row), oldAmount,
                    row.getAmount().subtract(oldAmount));
            events.publish("WholesaleUsageReratedEvent", "wholesaleUsageLedger", event, tenant);
            rerated.add(event);
        }
        return rerated;
    }

    /**
     * P4 — THE NEGOTIATION TWIN: replay the period's REAL CDRs against a
     * HYPOTHETICAL rate card ("what if the host gave us data at 1.50?").
     * Read-only — nothing is rated, booked or stored; the answer is a
     * comparison with its assumptions on the face. The MVNO's side of the
     * table at the renegotiation.
     */
    @Transactional(readOnly = true)
    public WholesaleSimulation simulateWholesale(LocalDate periodStart, LocalDate periodEnd,
            List<SimulateWholesaleRequest.ProposedRate> proposedRates) {
        String tenant = tenantScope.currentTenantId();
        Map<String, BigDecimal> units = unitsBySpec(tenant, periodStart, periodEnd);
        Map<String, BigDecimal> proposal = new LinkedHashMap<>();
        for (SimulateWholesaleRequest.ProposedRate r : proposedRates == null ? List.<SimulateWholesaleRequest.ProposedRate>of() : proposedRates) {
            if (r.usageSpecName() != null && r.wholesaleRate() != null) {
                proposal.put(r.usageSpecName(), r.wholesaleRate());
            }
        }
        List<WholesaleSimulation.Line> lines = new ArrayList<>();
        BigDecimal currentTotal = BigDecimal.ZERO;
        BigDecimal proposedTotal = BigDecimal.ZERO;
        String currency = null;
        for (Map.Entry<String, BigDecimal> e : units.entrySet()) {
            Optional<WholesaleRateCard> cardOpt = rateCards.findByTenantIdAndUsageSpecName(tenant, e.getKey());
            if (cardOpt.isEmpty()) {
                continue;   // no agreed rate today — nothing to compare against
            }
            WholesaleRateCard card = cardOpt.get();
            currency = currency != null ? currency : card.getCurrency();
            BigDecimal currentRate = card.getWholesaleRate();
            BigDecimal proposedRate = proposal.getOrDefault(e.getKey(), currentRate);
            BigDecimal currentCost = e.getValue().multiply(currentRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal proposedCost = e.getValue().multiply(proposedRate).setScale(2, RoundingMode.HALF_UP);
            currentTotal = currentTotal.add(currentCost);
            proposedTotal = proposedTotal.add(proposedCost);
            lines.add(new WholesaleSimulation.Line(e.getKey(), e.getValue(), card.getUnit(), currentRate,
                    proposedRate, currentCost, proposedCost, proposedCost.subtract(currentCost)));
        }
        return new WholesaleSimulation("WholesaleNegotiationSimulation", periodStart.toString(),
                periodEnd.toString(), lines, currentTotal, proposedTotal, proposedTotal.subtract(currentTotal),
                currency, List.of(
                        "units are the period's REAL CDRs — traffic mix assumed unchanged under the new rates",
                        "specs without a proposed rate keep the current agreed rate",
                        "read-only: nothing was rated, booked or stored"));
    }

    @Transactional(readOnly = true)
    public List<WholesaleLedgerView> ledgerFor(LocalDate periodStart) {
        return ledger.findByTenantIdAndPeriodStart(tenantScope.currentTenantId(), periodStart)
                .stream().map(this::ledgerView).toList();
    }

    /**
     * The settlement statement + a reconciliation view: the units the ledger was
     * rated on vs the units the CDRs show NOW. A mismatch (a CDR that landed after
     * rating) is flagged — revenue assurance, not just a sum.
     */
    @Transactional(readOnly = true)
    public WholesaleSettlement settlement(LocalDate periodStart, LocalDate periodEnd) {
        String tenant = tenantScope.currentTenantId();
        List<WholesaleUsageLedger> rows = ledger.findByTenantIdAndPeriodStart(tenant, periodStart);
        Map<String, BigDecimal> live = unitsBySpec(tenant, periodStart, periodEnd);
        List<WholesaleSettlement.Line> lines = new ArrayList<>();
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
            lines.add(new WholesaleSettlement.Line(row.getUsageSpecName(), row.getTotalUnits(), liveUnits,
                    row.getUnit(), row.getWholesaleRate(), row.getAmount(), row.getCurrency(), match));
            total = total.add(row.getAmount());
        }
        // the statement's currency is the ledger's, not a hardcoded default
        return new WholesaleSettlement("MobileWholesaleSettlement", "month", periodStart.toString(), host, lines,
                total.setScale(2, RoundingMode.HALF_UP),
                rows.isEmpty() || rows.get(0).getCurrency() == null ? "EUR" : rows.get(0).getCurrency(),
                reconciled);
    }

    /* ---------- rate card + IMSI admin ---------- */

    @Transactional
    public WholesaleRateCardView upsertRateCard(WholesaleRateCardRequest dto) {
        String tenant = tenantScope.currentTenantId();
        if (dto.usageSpecName() == null || dto.wholesaleRate() == null) {
            throw new BadRequestException("usageSpecName and wholesaleRate are required");
        }
        String spec = dto.usageSpecName();
        WholesaleRateCard card = rateCards.findByTenantIdAndUsageSpecName(tenant, spec)
                .orElseGet(WholesaleRateCard::new);
        if (card.getId() == null) {
            card.setId(UUID.randomUUID().toString());
            card.setTenantId(tenant);
            card.setUsageSpecName(spec);
        }
        card.setWholesaleRate(dto.wholesaleRate());
        card.setUnit(dto.unit());
        card.setCurrency(dto.currency() == null ? "EUR" : dto.currency());
        card.setHostPartyId(dto.hostPartyId());
        card.setHostName(dto.hostName());
        card.setLastUpdate(OffsetDateTime.now());
        return rateCardView(rateCards.save(card));
    }

    @Transactional(readOnly = true)
    public List<WholesaleRateCardView> rateCards() {
        return rateCards.findByTenantId(tenantScope.currentTenantId()).stream().map(this::rateCardView).toList();
    }

    @Transactional
    public ImsiRangeView allocateImsi(ImsiRangeRequest dto) {
        if (dto.fromImsi() == null || dto.toImsi() == null) {
            throw new BadRequestException("fromImsi and toImsi are required");
        }
        ImsiRange r = new ImsiRange();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setHostPartyId(dto.hostPartyId());
        r.setHostName(dto.hostName());
        r.setPrefix(dto.prefix());
        r.setFromImsi(dto.fromImsi());
        r.setToImsi(dto.toImsi());
        r.setCapacity(dto.capacity());
        r.setNote(dto.note());
        r.setAllocatedAt(OffsetDateTime.now());
        return imsiView(imsiRanges.save(r));
    }

    @Transactional(readOnly = true)
    public List<ImsiRangeView> imsiRanges() {
        return imsiRanges.findByTenantId(tenantScope.currentTenantId()).stream().map(this::imsiView).toList();
    }

    /* ---------- mappers ---------- */

    private WholesaleLedgerView ledgerView(WholesaleUsageLedger r) {
        boolean rerated = r.getRerateCount() > 0;
        return new WholesaleLedgerView(r.getId(),
                r.getPeriodStart() == null ? null : r.getPeriodStart().toString(),
                r.getUsageSpecName(), r.getTotalUnits(), r.getUnit(), r.getWholesaleRate(), r.getAmount(),
                r.getCurrency(), r.getHostPartyId(), r.getStatus(),
                rerated ? r.getRerateCount() : null, rerated ? r.getLastReratedAt() : null,
                "WholesaleUsageLedger");
    }

    private WholesaleRateCardView rateCardView(WholesaleRateCard c) {
        return new WholesaleRateCardView(c.getId(), c.getUsageSpecName(), c.getWholesaleRate(), c.getUnit(),
                c.getCurrency(), c.getHostPartyId(), c.getHostName(), "WholesaleRateCard");
    }

    private ImsiRangeView imsiView(ImsiRange r) {
        return new ImsiRangeView(r.getId(), r.getHostPartyId(), r.getHostName(), r.getPrefix(), r.getFromImsi(),
                r.getToImsi(), r.getCapacity(), r.getNote(), "ImsiRange");
    }
}
