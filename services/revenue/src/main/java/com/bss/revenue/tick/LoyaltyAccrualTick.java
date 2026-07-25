package com.bss.revenue.tick;

import com.bss.revenue.security.TenantContext;
import com.bss.revenue.security.TenantRegistry;
import com.bss.revenue.service.RevenueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily loyalty accrual per tenant — fleet-safe (TickGuard lease), no-op
 * unless finance configured a currency-per-point.
 */
@Component
public class LoyaltyAccrualTick {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyAccrualTick.class);

    private final RevenueService revenue;
    private final TenantRegistry tenants;
    private final TickGuard guard;

    public LoyaltyAccrualTick(RevenueService revenue, TenantRegistry tenants, TickGuard guard) {
        this.revenue = revenue;
        this.tenants = tenants;
        this.guard = guard;
    }

    @Scheduled(fixedDelayString = "${bss.revenue.accrual-interval-ms:86400000}",
            initialDelayString = "${bss.revenue.accrual-initial-delay-ms:600000}")
    public void accrue() {
        if (!guard.claim("revenue-loyalty-accrual", java.time.Duration.ofMinutes(10))) {
            return;
        }
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                Object result = revenue.loyaltyAccrual();
                log.info("loyalty accrual [{}]: {}", tenant.getId(), result);
            } catch (Exception e) {
                log.warn("loyalty accrual [{}] failed: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
