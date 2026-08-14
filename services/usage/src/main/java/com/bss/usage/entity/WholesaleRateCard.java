package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The wholesale rates an MVNO (this tenant) pays its host MNO, per usage type. */
@Entity
@Table(name = "wholesale_rate_card")
public class WholesaleRateCard {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "usage_spec_name", nullable = false, length = 64)
    private String usageSpecName;

    @Column(name = "wholesale_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal wholesaleRate;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "host_party_id", length = 64)
    private String hostPartyId;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public WholesaleRateCard() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUsageSpecName() { return usageSpecName; }
    public void setUsageSpecName(String usageSpecName) { this.usageSpecName = usageSpecName; }
    public BigDecimal getWholesaleRate() { return wholesaleRate; }
    public void setWholesaleRate(BigDecimal wholesaleRate) { this.wholesaleRate = wholesaleRate; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getHostPartyId() { return hostPartyId; }
    public void setHostPartyId(String hostPartyId) { this.hostPartyId = hostPartyId; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
