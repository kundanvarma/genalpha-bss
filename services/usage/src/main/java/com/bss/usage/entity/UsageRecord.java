package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usage_record")
public class UsageRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "usage_spec_name")
    private String usageSpecName;

    @Column(name = "usage_date")
    private OffsetDateTime usageDate;

    @Column(name = "usage_value")
    private BigDecimal value;

    @Column(name = "units")
    private String units;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "product_offering_id")
    private String productOfferingId;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UsageRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getUsageSpecName() {
        return usageSpecName;
    }

    public void setUsageSpecName(String usageSpecName) {
        this.usageSpecName = usageSpecName;
    }

    public OffsetDateTime getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(OffsetDateTime usageDate) {
        this.usageDate = usageDate;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getProductOfferingId() {
        return productOfferingId;
    }

    public void setProductOfferingId(String productOfferingId) {
        this.productOfferingId = productOfferingId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @jakarta.persistence.Column(name = "payload_json", length = 8000)
    private String payloadJson;
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    /** NULL = home network; set = the roaming zone this traffic burned in. */
    @Column(name = "zone")
    private String zone;
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }

    /** The portion of this record the household pool covered (never rates
     * against the personal allowance); NULL = none. */
    @Column(name = "pooled_value")
    private BigDecimal pooledValue;
    public BigDecimal getPooledValue() { return pooledValue; }
    public void setPooledValue(BigDecimal pooledValue) { this.pooledValue = pooledValue; }

    /** This record's usage net of what the pool absorbed. */
    public BigDecimal unpooledValue() {
        return pooledValue == null ? value : value.subtract(pooledValue);
    }
}
