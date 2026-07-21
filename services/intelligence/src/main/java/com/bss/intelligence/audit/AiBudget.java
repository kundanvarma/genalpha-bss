package com.bss.intelligence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A tenant's AI spend ceiling and kill-switch — OPERATIONAL state an
 * operator sets, not static config. No row means unlimited and enabled,
 * so no tenant is ever starved by default. The governor sums spend from
 * the audit ledger over the trailing window and refuses fail-closed when
 * the ceiling is crossed.
 */
@Entity
@Table(name = "ai_budget")
public class AiBudget {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 0 = unlimited. */
    @Column(name = "budget_micros", nullable = false)
    private long budgetMicros;

    @Column(name = "window_hours", nullable = false)
    private int windowHours = 720;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public long getBudgetMicros() { return budgetMicros; }
    public void setBudgetMicros(long v) { this.budgetMicros = v; }
    public int getWindowHours() { return windowHours; }
    public void setWindowHours(int v) { this.windowHours = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
