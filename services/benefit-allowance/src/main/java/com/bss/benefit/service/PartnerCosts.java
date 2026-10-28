package com.bss.benefit.service;

import com.bss.benefit.dto.ChoiceEvents;
import com.bss.benefit.dto.PartnerCostView;
import com.bss.benefit.entity.ChoicePool;
import com.bss.benefit.entity.ChoiceAllocation;
import com.bss.benefit.entity.PartnerCost;
import com.bss.benefit.entity.PartnerInvoice;
import com.bss.benefit.events.DomainEventPublisher;
import com.bss.benefit.repository.ChoiceAllocationRepository;
import com.bss.benefit.repository.ChoicePoolRepository;
import com.bss.benefit.repository.PartnerCostRepository;
import com.bss.benefit.repository.PartnerInvoiceRepository;
import com.bss.benefit.security.TenantContext;
import com.bss.benefit.security.TenantRegistry;
import com.bss.benefit.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The partner cost ledger (#360). A benefit the customer picked "for free"
 * costs the operator its wholesale price every month it is active: one row per
 * active benefit per calendar month (UTC), written when the benefit goes
 * active and again by a sweep as each new month begins. The (allocation,
 * month) key makes the recording idempotent — a replayed completion or a
 * second sweep writes nothing — and every row publishes ChoiceCostRecordedEvent
 * for revenue to post as cost of sales and a payable to the partner.
 * <p>
 * Whole months, never prorated: a benefit active on any day of a month costs
 * that month. That is how the seeded partner terms read; a partner that bills
 * by the day needs a proration rule here first.
 */
@Service
public class PartnerCosts {

    private static final Logger log = LoggerFactory.getLogger(PartnerCosts.class);

    private final PartnerCostRepository costs;
    private final ChoiceAllocationRepository allocations;
    private final ChoicePoolRepository pools;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;
    private final PartnerInvoiceRepository invoices;

    public PartnerCosts(PartnerCostRepository costs, ChoiceAllocationRepository allocations,
            ChoicePoolRepository pools, TenantScope tenantScope, TenantRegistry registry, DomainEventPublisher events,
            TransactionTemplate tx, PartnerInvoiceRepository invoices) {
        this.costs = costs;
        this.allocations = allocations;
        this.pools = pools;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.events = events;
        this.tx = tx;
        this.invoices = invoices;
    }

    static String period(OffsetDateTime at) {
        return YearMonth.from(at.withOffsetSameInstant(ZoneOffset.UTC)).toString();
    }

    /** Record this month's cost of an active benefit, once. Runs inside the caller's transaction. */
    public void record(ChoiceAllocation a) {
        if (a.getWholesaleCost() == null || a.getPartner() == null || a.getPartner().isBlank()) {
            return;   // an in-house benefit costs no partner anything
        }
        String tenant = a.getTenantId();
        String month = period(OffsetDateTime.now());
        if (costs.existsByTenantIdAndAllocationIdAndPeriod(tenant, a.getId(), month)) {
            return;
        }
        PartnerCost c = new PartnerCost();
        c.setTenantId(tenant);
        c.setId(UUID.randomUUID().toString());
        c.setAllocationId(a.getId());
        c.setPeriod(month);
        c.setPartner(a.getPartner());
        c.setOfferingName(a.getOfferingName());
        c.setAmount(a.getWholesaleCost());
        c.setCurrency(currency(tenant));
        c.setRecordedAt(OffsetDateTime.now());
        costs.save(c);
        events.publish("ChoiceCostRecordedEvent", "partnerCost", new PartnerCostView.Recorded(c.getId(),
                a.getId(), month, c.getPartner(), c.getOfferingName(), c.getAmount(), c.getCurrency(),
                ChoiceEvents.PartyRef.customer(pools.findById(a.getPoolId()).map(ChoicePool::getOwnerPartyId)
                        .orElse(null))), tenant);
    }

    /** Every tenant that offers Choices: each active benefit's cost for the month now running. */
    @Scheduled(initialDelayString = "${bss.choices.cost-sweep-initial-ms:60000}",
            fixedDelayString = "${bss.choices.cost-sweep-interval-ms:3600000}")
    public void sweep() {
        for (TenantRegistry.TenantEntry t : registry.getRegistry()) {
            if (!t.isChoices()) {
                continue;
            }
            try (TenantContext ignored = TenantContext.actAs(t.getId())) {
                for (ChoiceAllocation a : allocations.findByTenantIdAndState(t.getId(), ChoiceLedger.COMMITTED)) {
                    recordAlone(a);
                }
            }
        }
    }

    private void recordAlone(ChoiceAllocation a) {
        try {
            tx.executeWithoutResult(s -> record(a));
        } catch (DataIntegrityViolationException raced) {
            // a completion recorded the same month a moment ago: the key held, nothing to do
            log.debug("partner cost for {} already recorded", a.getId());
        }
    }

    /** The month's bill, partner by partner. */
    public PartnerCostView month(String month) {
        String tenant = tenantScope.currentTenantId();
        List<PartnerCost> rows = costs.findByTenantIdAndPeriodOrderByPartnerAsc(tenant, month);
        Map<String, PartnerCostView.Line> lines = new TreeMap<>();
        for (PartnerCost c : rows) {
            lines.merge(c.getPartner() + "\u0000" + c.getOfferingName(),
                    new PartnerCostView.Line(c.getPartner(), c.getOfferingName(), 1, c.getAmount()),
                    (x, y) -> new PartnerCostView.Line(x.partner(), x.offeringName(), x.active() + y.active(),
                            x.amount().add(y.amount())));
        }
        BigDecimal total = rows.stream().map(PartnerCost::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PartnerCostView(month, currency(tenant), total, List.copyOf(lines.values()),
                reconciled(tenant, month, rows));
    }

    /**
     * Each partner's month, owed beside invoiced (#390): every partner the ledger owes
     * or that sent an invoice, so an invoice for a month nothing was owed shows too.
     */
    private List<PartnerCostView.Partner> reconciled(String tenant, String month, List<PartnerCost> rows) {
        Map<String, BigDecimal> owed = new TreeMap<>();
        for (PartnerCost c : rows) {
            owed.merge(c.getPartner(), c.getAmount(), BigDecimal::add);
        }
        Map<String, List<PartnerInvoice>> billed = new TreeMap<>();
        for (PartnerInvoice i : invoices.findByTenantIdAndPeriodOrderByPartnerAscRecordedAtAsc(tenant, month)) {
            billed.computeIfAbsent(i.getPartner(), k -> new java.util.ArrayList<>()).add(i);
            owed.putIfAbsent(i.getPartner(), BigDecimal.ZERO);
        }
        return owed.entrySet().stream().map(e -> {
            List<PartnerInvoice> theirs = billed.getOrDefault(e.getKey(), List.of());
            BigDecimal invoiced = theirs.stream().map(PartnerInvoice::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PartnerCostView.Partner(e.getKey(), e.getValue(), invoiced, invoiced.subtract(e.getValue()),
                    theirs.stream().map(PartnerInvoices::view).toList());
        }).toList();
    }

    private String currency(String tenant) {
        TenantRegistry.TenantEntry t = registry.byId(tenant);
        return t == null || t.getCurrency() == null ? "EUR" : t.getCurrency();
    }
}
