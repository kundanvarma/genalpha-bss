package com.bss.usage.service;

import com.bss.usage.dto.MvnoStatement;
import com.bss.usage.dto.ProviderLedgerView;
import com.bss.usage.dto.ProviderLine;
import com.bss.usage.dto.ProviderRateCardRequest;
import com.bss.usage.dto.ProviderRateCardView;
import com.bss.usage.dto.ProviderSettlement;
import com.bss.usage.dto.ProviderUsageRequest;
import com.bss.usage.dto.ProviderUsageResult;
import com.bss.usage.entity.ProviderRateCard;
import com.bss.usage.entity.ProviderUsageLedger;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.repository.ProviderRateCardRepository;
import com.bss.usage.repository.ProviderUsageLedgerRepository;
import com.bss.usage.security.TenantScope;
import com.bss.usage.exception.BadRequestException;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Mobile wholesale PROVIDER face (W-M7): the host MNO / MVNE billing EXTERNAL
 * MVNOs (who run their own BSS) for the traffic they carry. Their usage is fed to
 * the host (a mediation feed from the network, reported per MVNO), rated at a
 * per-MVNO rate card — the SLA/tier lever — into the host's wholesale AR ledger,
 * and exposed both as the host's consolidated settlement and as a per-MVNO
 * statement an external MVNO's BSS pulls. The mirror of the fibre provider side.
 */
@Service
public class ProviderWholesaleService {

    private final ProviderRateCardRepository rateCards;
    private final ProviderUsageLedgerRepository ledger;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    private final int rerateWindowDays;
    private final TenantClock clock;

    public ProviderWholesaleService(ProviderRateCardRepository rateCards, ProviderUsageLedgerRepository ledger,
            DomainEventPublisher events, TenantScope tenantScope,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.usage.wholesale-rerate-window-days:45}") int rerateWindowDays,
            TenantClock clock) {
        this.clock = clock;
        this.rateCards = rateCards;
        this.ledger = ledger;
        this.events = events;
        this.tenantScope = tenantScope;
        this.rerateWindowDays = rerateWindowDays;
    }

    /** The rate to charge THIS MVNO for THIS usage type: its own card wins, else the default. */
    private Optional<ProviderRateCard> rateFor(String tenant, String mvnoPartyId, String spec) {
        Optional<ProviderRateCard> own = rateCards.findByTenantIdAndMvnoPartyIdAndUsageSpecName(tenant, mvnoPartyId, spec);
        return own.isPresent() ? own : rateCards.findByTenantIdAndMvnoPartyIdIsNullAndUsageSpecName(tenant, spec);
    }

    /**
     * The host records an external MVNO's usage for a period (the network's
     * mediation feed) and rates it. Idempotent: one ledger row per
     * (mvno, period, type) — a repeat report returns the existing row untouched,
     * so revenue never double-books.
     */
    @Transactional
    public ProviderUsageResult recordProviderUsage(ProviderUsageRequest dto) {
        String tenant = tenantScope.currentTenantId();
        String mvno = dto.mvnoPartyId();
        String spec = dto.usageSpecName();
        if (mvno == null || spec == null || dto.units() == null || dto.periodStart() == null) {
            throw new BadRequestException("mvnoPartyId, usageSpecName, units and periodStart are required");
        }
        LocalDate period = LocalDate.parse(dto.periodStart());
        BigDecimal units = dto.units();
        Optional<ProviderUsageLedger> existing =
                ledger.findByTenantIdAndMvnoPartyIdAndPeriodStartAndUsageSpecName(tenant, mvno, period, spec);
        if (existing.isPresent()) {
            return correctIfMoved(existing.get(), units);
        }
        ProviderRateCard card = rateFor(tenant, mvno, spec)
                .orElseThrow(() -> new BadRequestException(
                        "no provider rate card for MVNO " + mvno + " / " + spec + " (nor a default)"));
        ProviderUsageLedger row = new ProviderUsageLedger();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setMvnoPartyId(mvno);
        row.setMvnoName(dto.mvnoName() == null ? card.getMvnoName() : dto.mvnoName());
        row.setPeriodStart(period);
        row.setUsageSpecName(spec);
        row.setTotalUnits(units);
        row.setUnit(dto.unit() == null ? card.getUnit() : dto.unit());
        row.setRate(card.getRate());
        row.setAmount(units.multiply(card.getRate()).setScale(2, RoundingMode.HALF_UP));
        row.setCurrency(card.getCurrency() == null ? "EUR" : card.getCurrency());
        row.setStatus("rated");
        row.setCreatedAt(OffsetDateTime.now());
        ledger.save(row);
        // Revenue books the host's wholesale REVENUE off this event (amount rides it).
        events.publish("ProviderWholesaleRatedEvent", "providerUsageLedger", ledgerView(row));
        return ledgerView(row);
    }

    /**
     * THE PROVIDER-FACE CLOSED LOOP: the provider ledger has no CDR store of
     * its own — the network's mediation feed IS the source — so late CDRs
     * arrive as a corrected RE-REPORT of the same (mvno, period, type). An
     * unchanged repeat stays a free no-op; a changed one inside the window
     * re-rates the row, stamps the receipt (rerateCount, lastReratedAt) and
     * emits ProviderWholesaleReratedEvent carrying the DELTA so the host's
     * wholesale AR moves with the truth. Outside the window nothing mutates:
     * a settled old period is a dispute, not a silent correction.
     */
    private ProviderUsageResult correctIfMoved(ProviderUsageLedger row, BigDecimal units) {
        if (units.compareTo(row.getTotalUnits()) == 0) {
            return ledgerView(row);
        }
        if (row.getPeriodStart().isBefore(
                clock.today().minusDays(rerateWindowDays).withDayOfMonth(1))) {
            throw new BadRequestException("period " + row.getPeriodStart() + " is outside the "
                    + rerateWindowDays + "-day correction window — raise a dispute, the ledger "
                    + "does not silently rewrite settled history");
        }
        BigDecimal oldAmount = row.getAmount();
        row.setTotalUnits(units);
        row.setAmount(units.multiply(row.getRate()).setScale(2, RoundingMode.HALF_UP));
        row.setRerateCount(row.getRerateCount() + 1);
        row.setLastReratedAt(OffsetDateTime.now());
        ledger.save(row);
        ProviderUsageResult.Rerated event = new ProviderUsageResult.Rerated(ledgerView(row), oldAmount,
                row.getAmount().subtract(oldAmount), row.getRerateCount());
        events.publish("ProviderWholesaleReratedEvent", "providerUsageLedger", event);
        return event;
    }

    /** The host's consolidated settlement: per MVNO, what each owes — host AR. */
    @Transactional(readOnly = true)
    public ProviderSettlement providerSettlement(LocalDate periodStart) {
        String tenant = tenantScope.currentTenantId();
        List<ProviderUsageLedger> rows = ledger.findByTenantIdAndPeriodStart(tenant, periodStart);
        // grouped per MVNO in first-seen order; the name is the first row's
        Map<String, List<ProviderUsageLedger>> byMvno = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;
        for (ProviderUsageLedger r : rows) {
            byMvno.computeIfAbsent(r.getMvnoPartyId(), k -> new ArrayList<>()).add(r);
            grand = grand.add(r.getAmount());
        }
        List<ProviderSettlement.Mvno> mvnos = new ArrayList<>();
        for (List<ProviderUsageLedger> group : byMvno.values()) {
            BigDecimal total = BigDecimal.ZERO;
            List<ProviderLine> lines = new ArrayList<>();
            for (ProviderUsageLedger r : group) {
                lines.add(line(r));
                total = total.add(r.getAmount());
            }
            mvnos.add(new ProviderSettlement.Mvno(group.get(0).getMvnoPartyId(), group.get(0).getMvnoName(),
                    lines, total));
        }
        return new ProviderSettlement("MobileWholesaleProviderSettlement", "month", periodStart.toString(), mvnos,
                grand.setScale(2, RoundingMode.HALF_UP), "EUR");
    }

    /** One MVNO's statement — the face an external MVNO's BSS pulls to reconcile. */
    @Transactional(readOnly = true)
    public MvnoStatement mvnoStatement(String mvnoPartyId, LocalDate periodStart) {
        String tenant = tenantScope.currentTenantId();
        List<ProviderUsageLedger> rows =
                ledger.findByTenantIdAndMvnoPartyIdAndPeriodStart(tenant, mvnoPartyId, periodStart);
        List<ProviderLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String name = null;
        for (ProviderUsageLedger r : rows) {
            lines.add(line(r));
            total = total.add(r.getAmount());
            name = r.getMvnoName();
        }
        return new MvnoStatement("MobileWholesaleStatement", mvnoPartyId, name, periodStart.toString(), lines,
                total.setScale(2, RoundingMode.HALF_UP), "EUR");
    }

    @Transactional
    public ProviderRateCardView upsertProviderRateCard(ProviderRateCardRequest dto) {
        String tenant = tenantScope.currentTenantId();
        if (dto.usageSpecName() == null || dto.rate() == null) {
            throw new BadRequestException("usageSpecName and rate are required");
        }
        String mvno = dto.mvnoPartyId();   // null = default
        String spec = dto.usageSpecName();
        Optional<ProviderRateCard> found = mvno == null
                ? rateCards.findByTenantIdAndMvnoPartyIdIsNullAndUsageSpecName(tenant, spec)
                : rateCards.findByTenantIdAndMvnoPartyIdAndUsageSpecName(tenant, mvno, spec);
        ProviderRateCard c = found.orElseGet(ProviderRateCard::new);
        if (c.getId() == null) {
            c.setId(UUID.randomUUID().toString());
            c.setTenantId(tenant);
            c.setMvnoPartyId(mvno);
            c.setUsageSpecName(spec);
        }
        c.setMvnoName(dto.mvnoName());
        c.setRate(dto.rate());
        c.setUnit(dto.unit());
        c.setCurrency(dto.currency() == null ? "EUR" : dto.currency());
        c.setLastUpdate(OffsetDateTime.now());
        return rateCardView(rateCards.save(c));
    }

    @Transactional(readOnly = true)
    public List<ProviderRateCardView> providerRateCards() {
        return rateCards.findByTenantId(tenantScope.currentTenantId()).stream().map(this::rateCardView).toList();
    }

    private ProviderLine line(ProviderUsageLedger r) {
        return new ProviderLine(r.getUsageSpecName(), r.getTotalUnits(), r.getUnit(), r.getRate(), r.getAmount(),
                r.getCurrency());
    }

    private ProviderLedgerView ledgerView(ProviderUsageLedger r) {
        return new ProviderLedgerView(r.getId(), r.getMvnoPartyId(), r.getMvnoName(),
                r.getPeriodStart() == null ? null : r.getPeriodStart().toString(),
                r.getUsageSpecName(), r.getTotalUnits(), r.getUnit(), r.getRate(), r.getAmount(), r.getCurrency(),
                "ProviderUsageLedger");
    }

    private ProviderRateCardView rateCardView(ProviderRateCard c) {
        return new ProviderRateCardView(c.getId(), c.getMvnoPartyId(), c.getMvnoName(), c.getUsageSpecName(),
                c.getRate(), c.getUnit(), c.getCurrency(), "ProviderRateCard");
    }
}
