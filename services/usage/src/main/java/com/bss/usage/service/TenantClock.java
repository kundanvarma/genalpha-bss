package com.bss.usage.service;

import com.bss.usage.security.TenantRegistry;
import com.bss.usage.security.TenantScope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** T3 — the usage clock seam: wall time for everyone; a SANDBOX tenant with
 *  clock-offset-days lives ahead. Non-sandbox offsets are ignored here. */
@Component
public class TenantClock {

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public TenantClock(TenantRegistry tenants, TenantScope tenantScope) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    public LocalDate today() {
        TenantRegistry.TenantEntry te = tenants.byId(tenantScope.currentTenantId());
        if (te != null && te.isSandbox() && te.getClockOffsetDays() > 0) {
            return LocalDate.now(ZoneOffset.UTC).plusDays(te.getClockOffsetDays());
        }
        return LocalDate.now(ZoneOffset.UTC);
    }
}
