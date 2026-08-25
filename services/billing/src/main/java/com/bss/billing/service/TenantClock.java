package com.bss.billing.service;

import com.bss.billing.security.TenantRegistry;
import com.bss.billing.security.TenantScope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * T1 — the clock seam: wall time for everyone; a SANDBOX tenant whose fleet
 * block carries clock-offset-days lives that many days in the future. The
 * gate is structural: a non-sandbox offset is ignored here, so production
 * time is not a knob no matter what reaches the file.
 */
@Component
public class TenantClock {

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public TenantClock(TenantRegistry tenants, TenantScope tenantScope) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    public java.time.OffsetDateTime now() {
        TenantRegistry.TenantEntry te = tenants.byId(tenantScope.currentTenantId());
        if (te != null && te.isSandbox() && te.getClockOffsetDays() > 0) {
            return java.time.OffsetDateTime.now().plusDays(te.getClockOffsetDays());
        }
        return java.time.OffsetDateTime.now();
    }

    public LocalDate today() {
        TenantRegistry.TenantEntry te = tenants.byId(tenantScope.currentTenantId());
        if (te != null && te.isSandbox() && te.getClockOffsetDays() > 0) {
            return LocalDate.now().plusDays(te.getClockOffsetDays());
        }
        return LocalDate.now();
    }
}
