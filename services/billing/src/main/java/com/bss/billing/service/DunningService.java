package com.bss.billing.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Duration grace;

    public DunningService(InstallmentPlanRepository plans, CustomerBillRepository bills,
            DomainEventPublisher events, TenantRegistry tenants,
            @Value("${bss.billing.dunning-grace-ms:604800000}") long graceMs) {
        this.plans = plans;
        this.bills = bills;
        this.events = events;
        this.tenants = tenants;
        this.grace = Duration.ofMillis(graceMs);
    }

    @Scheduled(fixedDelayString = "${bss.billing.dunning-tick-ms:60000}")
    public void sweep() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                sweepTenant(tenant.getId());
            } catch (Exception e) {
                log.warn("dunning sweep failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void sweepTenant(String tenantId) {
        for (InstallmentPlan plan : plans.findTop100ByTenantIdAndStatusAndNextDueAtBefore(
                tenantId, InstallmentPlan.ACTIVE, OffsetDateTime.now())) {
            CustomerBill bill = bills.findByIdAndTenantId(plan.getBillId(), tenantId).orElse(null);
            if (bill == null) {
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
            } else if (plan.getRemindedAt().plus(grace).isBefore(OffsetDateTime.now())) {
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
    public List<Map<String, Object>> dunningView(String tenantId) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
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

    private Map<String, Object> dunningEvent(InstallmentPlan plan, CustomerBill bill) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("billId", bill.getId());
        map.put("billNo", bill.getBillNo());
        map.put("partyId", bill.getOwnerPartyId());
        map.put("installments", plan.getInstallments());
        map.put("paidCount", plan.getPaidCount());
        map.put("remaining", plan.remainingOf(bill.getAmountDueValue()));
        map.put("currency", plan.getCurrency());
        map.put("status", plan.getStatus());
        if (plan.getNextDueAt() != null) {
            map.put("nextDueAt", plan.getNextDueAt().toString());
        }
        if (plan.getRemindedAt() != null) {
            map.put("remindedAt", plan.getRemindedAt().toString());
        }
        map.put("graceDays", Math.max(1, grace.toDays()));
        map.put("relatedParty", List.of(Map.of("id", bill.getOwnerPartyId(), "role", "customer")));
        map.put("@type", "DunningCase");
        return map;
    }
}
