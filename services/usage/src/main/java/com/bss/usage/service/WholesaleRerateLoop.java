package com.bss.usage.service;

import com.bss.usage.security.TenantContext;
import com.bss.usage.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The tick that CLOSES the late-CDR loop: reconciliation's "a CDR landed
 * after rating" flag stops being a to-do for a human and becomes an
 * automatic re-rate + booked delta. Every tenant is swept on a fixed
 * delay; the window keeps old, settled periods untouchable (a dispute,
 * not a silent mutation).
 */
@Component
public class WholesaleRerateLoop {

    private static final Logger log = LoggerFactory.getLogger(WholesaleRerateLoop.class);

    private final WholesaleUsageService wholesale;
    private final TenantRegistry tenants;
    private final int windowDays;

    public WholesaleRerateLoop(WholesaleUsageService wholesale, TenantRegistry tenants,
            @Value("${bss.usage.wholesale-rerate-window-days:45}") int windowDays) {
        this.wholesale = wholesale;
        this.tenants = tenants;
        this.windowDays = windowDays;
    }

    @Scheduled(fixedDelayString = "${bss.usage.wholesale-rerate-tick-ms:300000}")
    public void sweep() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                List<Map<String, Object>> rerated = wholesale.rerateDrifted(tenant.getId(), windowDays);
                for (Map<String, Object> row : rerated) {
                    log.info("late-CDR re-rate: tenant {} {} {} -> {} (delta {}) — rerate #{}",
                            tenant.getId(), row.get("usageSpecName"), row.get("previousAmount"),
                            row.get("amount"), row.get("delta"), row.get("rerateCount"));
                }
            } catch (RuntimeException e) {
                log.warn("late-CDR sweep skipped for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
