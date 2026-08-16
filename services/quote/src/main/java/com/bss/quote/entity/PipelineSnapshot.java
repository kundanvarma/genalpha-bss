package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A point-in-time capture of the open weighted forecast — forecast-over-time. */
@Entity
@Table(name = "pipeline_snapshot")
public class PipelineSnapshot {
    @Id @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "captured_at", nullable = false) private OffsetDateTime capturedAt;
    @Column(name = "open_count", nullable = false) private int openCount;
    @Column(name = "open_amount", nullable = false, precision = 14, scale = 2) private BigDecimal openAmount;
    @Column(name = "weighted_forecast", nullable = false, precision = 14, scale = 2) private BigDecimal weightedForecast;
    @Column(name = "currency", nullable = false, length = 8) private String currency;

    public String getId() { return id; } public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { this.tenantId = v; }
    public OffsetDateTime getCapturedAt() { return capturedAt; } public void setCapturedAt(OffsetDateTime v) { this.capturedAt = v; }
    public int getOpenCount() { return openCount; } public void setOpenCount(int v) { this.openCount = v; }
    public BigDecimal getOpenAmount() { return openAmount; } public void setOpenAmount(BigDecimal v) { this.openAmount = v; }
    public BigDecimal getWeightedForecast() { return weightedForecast; } public void setWeightedForecast(BigDecimal v) { this.weightedForecast = v; }
    public String getCurrency() { return currency; } public void setCurrency(String v) { this.currency = v; }
}
