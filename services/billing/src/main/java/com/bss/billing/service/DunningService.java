package com.bss.billing.service;

import com.bss.billing.dto.DunningCaseView;
import com.bss.billing.dto.RelatedPartyRef;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.InstallmentPlan;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.InstallmentPlanRepository;
import com.bss.billing.security.TenantContext;
import com.bss.billing.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DUNNING, the polite kind first: an overdue installment gets exactly ONE
 * reminder; still unpaid after the grace period, the plan BREAKS — the
 * remaining balance falls due at once (the acceleration clause, as a
 * column instead of small print) and the customer is told in the same
 * plain words the plan was sold with. The sweep runs tenant by tenant,
 * acting as each so the row-level policies admit the reads.
 */
@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final InstallmentPlanRepository plans;
    private final CustomerBillRepository bills;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final DisputeService disputeService;
    private final com.bss.billing.tick.TickGuard tickGuard;
    private final Duration grace;
    private final TenantClock clock;

    public DunningService(InstallmentPlanRepository plans, CustomerBillRepository bills,
            DomainEventPublisher events, TenantRegistry tenants,
            DisputeService disputeService, com.bss.billing.tick.TickGuard tickGuard,
            @Value("${bss.billing.dunning-grace-ms:604800000}") long graceMs,
            TenantClock clock) {
        this.clock = clock;
        this.plans = plans;
        this.bills = bills;
        this.events = events;
        this.tenants = tenants;
        this.disputeService = disputeService;
        this.tickGuard = tickGuard;
        this.grace = Duration.ofMillis(graceMs);
    }

    @Scheduled(fixedDelayString = "${bss.billing.dunning-tick-ms:60000}")
    public void sweep() {
        if (!tickGuard.claim("dunning", Duration.ofSeconds(60))) {
            return; // another replica is dunning — one reminder, never two
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    sweepTenant(tenant.getId());
                } catch (Exception e) {
                    log.warn("dunning sweep failed for tenant {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("dunning");
        }
    }

    @Transactional
    public void sweepTenant(String tenantId) {
        for (InstallmentPlan plan : plans.findTop100ByTenantIdAndStatusAndNextDueAtBefore(
                tenantId, InstallmentPlan.ACTIVE, clock.now())) {   // T2: overdue is decided on the tenant clock
            CustomerBill bill = bills.findByIdAndTenantId(plan.getBillId(), tenantId).orElse(null);
            if (bill == null) {
                continue;
            }
            // collection never chases contested money
            if (disputeService.hasOpenDispute(tenantId, bill.getId())) {
                continue;
            }
            if (plan.getRemindedAt() == null) {
                plan.setRemindedAt(OffsetDateTime.now());
                plan.setLastUpdate(OffsetDateTime.now());
                plans.save(plan);
                events.publish("InstallmentOverdueEvent", "installmentPlan",
                        dunningEvent(plan, bill));
                log.info("dunning: reminded {} about bill {} (part {} of {})",
                        bill.getOwnerPartyId(), bill.getBillNo(),
                        plan.getPaidCount() + 1, plan.getInstallments());
            } else if (plan.getRemindedAt().plus(grace).isBefore(clock.now())) {
                plan.setStatus(InstallmentPlan.BROKEN);
                plan.setNextDueAt(null);
                plan.setLastUpdate(OffsetDateTime.now());
                plans.save(plan);
                events.publish("InstallmentPlanBrokenEvent", "installmentPlan",
                        dunningEvent(plan, bill));
                log.info("dunning: plan BROKEN for bill {} — {} {} now due at once",
                        bill.getBillNo(), plan.remainingOf(bill.getAmountDueValue()),
                        plan.getCurrency());
            }
        }
    }

    /** The staff window: who is overdue, who broke, what is still owed. */
    @Transactional(readOnly = true)
    public List<DunningCaseView> dunningView(String tenantId) {
        List<DunningCaseView> rows = new java.util.ArrayList<>();
        for (InstallmentPlan plan : plans.findByTenantIdAndStatusIn(tenantId,
                List.of(InstallmentPlan.ACTIVE, InstallmentPlan.BROKEN))) {
            boolean overdue = InstallmentPlan.BROKEN.equals(plan.getStatus())
                    || (plan.getNextDueAt() != null && plan.getNextDueAt().isBefore(OffsetDateTime.now()));
            if (!overdue) {
                continue;
            }
            CustomerBill bill = bills.findByIdAndTenantId(plan.getBillId(), tenantId).orElse(null);
            if (bill == null) {
                continue;
            }
            rows.add(dunningEvent(plan, bill));
        }
        return rows;
    }

    /** The same record is the staff window's row and the dunning events' payload. */
    private DunningCaseView dunningEvent(InstallmentPlan plan, CustomerBill bill) {
        return new DunningCaseView(bill.getId(), bill.getBillNo(), bill.getOwnerPartyId(),
                plan.getInstallments(), plan.getPaidCount(), plan.remainingOf(bill.getAmountDueValue()),
                plan.getCurrency(), plan.getStatus(),
                plan.getNextDueAt() == null ? null : plan.getNextDueAt().toString(),
                plan.getRemindedAt() == null ? null : plan.getRemindedAt().toString(),
                Math.max(1, grace.toDays()),
                List.of(RelatedPartyRef.customer(bill.getOwnerPartyId())), "DunningCase");
    }
}
