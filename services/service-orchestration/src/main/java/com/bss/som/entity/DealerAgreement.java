package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Being a dealer IS a row: the retail org and its commission per
 * activation. No row, no dealer powers. */
@Entity
@Table(name = "dealer_agreement")
public class DealerAgreement {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "dealer_org_id", nullable = false)
    private String dealerOrgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "commission_value", nullable = false)
    private BigDecimal commissionValue;

    @Column(name = "commission_unit", nullable = false)
    private String commissionUnit;

    /** The chain's own POS speaks as this OAuth2 client (client
     * credentials) — the credential IS the dealer, machine edition. */
    @Column(name = "client_id")
    private String clientId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDealerOrgId() { return dealerOrgId; }
    public void setDealerOrgId(String v) { this.dealerOrgId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public BigDecimal getCommissionValue() { return commissionValue; }
    public void setCommissionValue(BigDecimal v) { this.commissionValue = v; }
    public String getCommissionUnit() { return commissionUnit; }
    public void setCommissionUnit(String v) { this.commissionUnit = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String v) { this.clientId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
