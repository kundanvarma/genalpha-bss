package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** What the host MNO charges an MVNO per usage unit. A per-MVNO row overrides the
 *  default (mvnoPartyId = null) — the SLA/tier lever: a premium MVNO pays more. */
@Entity
@Table(name = "provider_rate_card")
public class ProviderRateCard {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "mvno_party_id", length = 64)
    private String mvnoPartyId;

    @Column(name = "mvno_name")
    private String mvnoName;

    @Column(name = "usage_spec_name", nullable = false, length = 64)
    private String usageSpecName;

    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public ProviderRateCard() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMvnoPartyId() { return mvnoPartyId; }
    public void setMvnoPartyId(String mvnoPartyId) { this.mvnoPartyId = mvnoPartyId; }
    public String getMvnoName() { return mvnoName; }
    public void setMvnoName(String mvnoName) { this.mvnoName = mvnoName; }
    public String getUsageSpecName() { return usageSpecName; }
    public void setUsageSpecName(String usageSpecName) { this.usageSpecName = usageSpecName; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
