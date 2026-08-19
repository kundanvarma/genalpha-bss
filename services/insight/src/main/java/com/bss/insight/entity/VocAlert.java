package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One VoC deviation, once per (aspect, ISO week) — with the numbers that
 * tripped it, so the alert is auditable, not a vibe. */
@Entity
@Table(name = "voc_alert")
public class VocAlert {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String aspect;

    @Column(name = "iso_week", nullable = false)
    private String isoWeek;

    @Column(name = "week_negatives", nullable = false)
    private int weekNegatives;

    @Column(name = "baseline_avg", nullable = false)
    private BigDecimal baselineAvg;

    @Column(nullable = false)
    private BigDecimal ratio;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getAspect() { return aspect; }
    public void setAspect(String v) { this.aspect = v; }
    public String getIsoWeek() { return isoWeek; }
    public void setIsoWeek(String v) { this.isoWeek = v; }
    public int getWeekNegatives() { return weekNegatives; }
    public void setWeekNegatives(int v) { this.weekNegatives = v; }
    public BigDecimal getBaselineAvg() { return baselineAvg; }
    public void setBaselineAvg(BigDecimal v) { this.baselineAvg = v; }
    public BigDecimal getRatio() { return ratio; }
    public void setRatio(BigDecimal v) { this.ratio = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
