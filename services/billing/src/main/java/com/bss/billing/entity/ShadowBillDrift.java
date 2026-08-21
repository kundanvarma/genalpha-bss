package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One product whose NEXT bill will differ from its last one — caught by the
 *  shadow run before the invoice is wrong. */
@Entity
@Table(name = "shadow_bill_drift")
public class ShadowBillDrift {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "owner_party_id", nullable = false, length = 64)
    private String ownerPartyId;

    @Column(name = "bill_id", length = 36)
    private String billId;

    @Column(name = "offering_id", length = 64)
    private String offeringId;

    @Column(name = "offering_name")
    private String offeringName;

    @Column(name = "billed_monthly", nullable = false, precision = 14, scale = 2)
    private BigDecimal billedMonthly;

    @Column(name = "current_monthly", nullable = false, precision = 14, scale = 2)
    private BigDecimal currentMonthly;

    @Column(name = "delta", nullable = false, precision = 14, scale = 2)
    private BigDecimal delta;

    @Column(name = "unit", length = 8)
    private String unit;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String v) { this.ownerPartyId = v; }
    public String getBillId() { return billId; }
    public void setBillId(String v) { this.billId = v; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String v) { this.offeringId = v; }
    public String getOfferingName() { return offeringName; }
    public void setOfferingName(String v) { this.offeringName = v; }
    public BigDecimal getBilledMonthly() { return billedMonthly; }
    public void setBilledMonthly(BigDecimal v) { this.billedMonthly = v; }
    public BigDecimal getCurrentMonthly() { return currentMonthly; }
    public void setCurrentMonthly(BigDecimal v) { this.currentMonthly = v; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal v) { this.delta = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(OffsetDateTime v) { this.detectedAt = v; }
}
