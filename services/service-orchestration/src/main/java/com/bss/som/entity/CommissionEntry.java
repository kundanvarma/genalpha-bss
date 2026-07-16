package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Money OUT with money-in discipline: PENDING on activation, EARNED
 * after the withdrawal window, CLAWED_BACK with the reason when the
 * customer leaves inside it. Every entry explainable. */
@Entity
@Table(name = "commission_entry")
public class CommissionEntry {

    public static final String PENDING = "pending";
    public static final String EARNED = "earned";
    public static final String CLAWED_BACK = "clawedBack";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "dealer_org_id", nullable = false)
    private String dealerOrgId;

    private String store;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "service_id")
    private String serviceId;

    @Column(name = "customer_party_id")
    private String customerPartyId;

    @Column(name = "offering_name")
    private String offeringName;

    /** The chain's own device sold alongside (their stock, their money) —
     * context only, never a billable item here. */
    @Column(name = "device_note")
    private String deviceNote;

    @Column(name = "amount_value", nullable = false)
    private BigDecimal amountValue;

    @Column(name = "amount_unit", nullable = false)
    private String amountUnit;

    @Column(nullable = false)
    private String status;

    private String reason;

    @Column(name = "accrued_at", nullable = false)
    private OffsetDateTime accruedAt;

    @Column(name = "hardens_at", nullable = false)
    private OffsetDateTime hardensAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDealerOrgId() { return dealerOrgId; }
    public void setDealerOrgId(String v) { this.dealerOrgId = v; }
    public String getStore() { return store; }
    public void setStore(String v) { this.store = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { this.serviceId = v; }
    public String getCustomerPartyId() { return customerPartyId; }
    public void setCustomerPartyId(String v) { this.customerPartyId = v; }
    public String getOfferingName() { return offeringName; }
    public void setOfferingName(String v) { this.offeringName = v; }
    public String getDeviceNote() { return deviceNote; }
    public void setDeviceNote(String v) { this.deviceNote = v; }
    public BigDecimal getAmountValue() { return amountValue; }
    public void setAmountValue(BigDecimal v) { this.amountValue = v; }
    public String getAmountUnit() { return amountUnit; }
    public void setAmountUnit(String v) { this.amountUnit = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public OffsetDateTime getAccruedAt() { return accruedAt; }
    public void setAccruedAt(OffsetDateTime v) { this.accruedAt = v; }
    public OffsetDateTime getHardensAt() { return hardensAt; }
    public void setHardensAt(OffsetDateTime v) { this.hardensAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
