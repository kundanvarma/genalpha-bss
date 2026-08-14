package com.bss.usage.service;

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

    public ProviderWholesaleService(ProviderRateCardRepository rateCards, ProviderUsageLedgerRepository ledger,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.rateCards = rateCards;
        this.ledger = ledger;
        this.events = events;
        this.tenantScope = tenantScope;
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
    public Map<String, Object> recordProviderUsage(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String mvno = str(dto.get("mvnoPartyId"));
        String spec = str(dto.get("usageSpecName"));
        if (mvno == null || spec == null || dto.get("units") == null || dto.get("periodStart") == null) {
            throw new BadRequestException("mvnoPartyId, usageSpecName, units and periodStart are required");
        }
        LocalDate period = LocalDate.parse(str(dto.get("periodStart")));
        BigDecimal units = new BigDecimal(str(dto.get("units")));
        Optional<ProviderUsageLedger> existing =
                ledger.findByTenantIdAndMvnoPartyIdAndPeriodStartAndUsageSpecName(tenant, mvno, period, spec);
        if (existing.isPresent()) {
            return ledgerMap(existing.get());
        }
        ProviderRateCard card = rateFor(tenant, mvno, spec)
                .orElseThrow(() -> new BadRequestException(
                        "no provider rate card for MVNO " + mvno + " / " + spec + " (nor a default)"));
        ProviderUsageLedger row = new ProviderUsageLedger();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setMvnoPartyId(mvno);
        row.setMvnoName(dto.get("mvnoName") == null ? card.getMvnoName() : str(dto.get("mvnoName")));
        row.setPeriodStart(period);
        row.setUsageSpecName(spec);
        row.setTotalUnits(units);
        row.setUnit(dto.get("unit") == null ? card.getUnit() : str(dto.get("unit")));
        row.setRate(card.getRate());
        row.setAmount(units.multiply(card.getRate()).setScale(2, RoundingMode.HALF_UP));
        row.setCurrency(card.getCurrency() == null ? "EUR" : card.getCurrency());
        row.setStatus("rated");
        row.setCreatedAt(OffsetDateTime.now());
        ledger.save(row);
        // Revenue books the host's wholesale REVENUE off this event (amount rides it).
        events.publish("ProviderWholesaleRatedEvent", "providerUsageLedger", ledgerMap(row));
        return ledgerMap(row);
    }

    /** The host's consolidated settlement: per MVNO, what each owes — host AR. */
    @Transactional(readOnly = true)
    public Map<String, Object> providerSettlement(LocalDate periodStart) {
        String tenant = tenantScope.currentTenantId();
        List<ProviderUsageLedger> rows = ledger.findByTenantIdAndPeriodStart(tenant, periodStart);
        Map<String, Map<String, Object>> byMvno = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;
        for (ProviderUsageLedger r : rows) {
            Map<String, Object> m = byMvno.computeIfAbsent(r.getMvnoPartyId(), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("mvnoPartyId", r.getMvnoPartyId());
                x.put("mvnoName", r.getMvnoName());
                x.put("line", new ArrayList<Map<String, Object>>());
                x.put("total", BigDecimal.ZERO);
                return x;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) m.get("line");
            lines.add(lineMap(r));
            m.put("total", ((BigDecimal) m.get("total")).add(r.getAmount()));
            grand = grand.add(r.getAmount());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "MobileWholesaleProviderSettlement");
        out.put("periodType", "month");
        out.put("periodStart", periodStart.toString());
        out.put("mvno", new ArrayList<>(byMvno.values()));
        out.put("totalRevenue", grand.setScale(2, RoundingMode.HALF_UP));
        out.put("currency", "EUR");
        return out;
    }

    /** One MVNO's statement — the face an external MVNO's BSS pulls to reconcile. */
    @Transactional(readOnly = true)
    public Map<String, Object> mvnoStatement(String mvnoPartyId, LocalDate periodStart) {
        String tenant = tenantScope.currentTenantId();
        List<ProviderUsageLedger> rows =
                ledger.findByTenantIdAndMvnoPartyIdAndPeriodStart(tenant, mvnoPartyId, periodStart);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String name = null;
        for (ProviderUsageLedger r : rows) {
            lines.add(lineMap(r));
            total = total.add(r.getAmount());
            name = r.getMvnoName();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "MobileWholesaleStatement");
        out.put("mvnoPartyId", mvnoPartyId);
        out.put("mvnoName", name);
        out.put("periodStart", periodStart.toString());
        out.put("line", lines);
        out.put("totalOwed", total.setScale(2, RoundingMode.HALF_UP));
        out.put("currency", "EUR");
        return out;
    }

    @Transactional
    public Map<String, Object> upsertProviderRateCard(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String mvno = str(dto.get("mvnoPartyId"));   // null = default
        String spec = str(dto.get("usageSpecName"));
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
        c.setMvnoName(str(dto.get("mvnoName")));
        c.setRate(new BigDecimal(str(dto.get("rate"))));
        c.setUnit(str(dto.get("unit")));
        c.setCurrency(dto.get("currency") == null ? "EUR" : str(dto.get("currency")));
        c.setLastUpdate(OffsetDateTime.now());
        return rateCardMap(rateCards.save(c));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> providerRateCards() {
        return rateCards.findByTenantId(tenantScope.currentTenantId()).stream().map(this::rateCardMap).toList();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Map<String, Object> lineMap(ProviderUsageLedger r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usageSpecName", r.getUsageSpecName());
        m.put("totalUnits", r.getTotalUnits());
        m.put("unit", r.getUnit());
        m.put("rate", r.getRate());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        return m;
    }

    private Map<String, Object> ledgerMap(ProviderUsageLedger r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("mvnoPartyId", r.getMvnoPartyId());
        m.put("mvnoName", r.getMvnoName());
        m.put("periodStart", r.getPeriodStart() == null ? null : r.getPeriodStart().toString());
        m.put("usageSpecName", r.getUsageSpecName());
        m.put("totalUnits", r.getTotalUnits());
        m.put("unit", r.getUnit());
        m.put("rate", r.getRate());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("@type", "ProviderUsageLedger");
        return m;
    }

    private Map<String, Object> rateCardMap(ProviderRateCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("mvnoPartyId", c.getMvnoPartyId());
        m.put("mvnoName", c.getMvnoName());
        m.put("usageSpecName", c.getUsageSpecName());
        m.put("rate", c.getRate());
        m.put("unit", c.getUnit());
        m.put("currency", c.getCurrency());
        m.put("@type", "ProviderRateCard");
        return m;
    }
}
