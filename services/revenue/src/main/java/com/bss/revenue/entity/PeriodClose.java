package com.bss.revenue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The close marker: postings for bills dated on or before it refuse. */
@Entity
@Table(name = "period_close")
public class PeriodClose {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "closed_through")
    private LocalDate closedThrough;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDate getClosedThrough() {
        return closedThrough;
    }

    public void setClosedThrough(LocalDate closedThrough) {
        this.closedThrough = closedThrough;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(OffsetDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
