package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usage_allowance")
public class UsageAllowance {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "product_offering")
    private String productOfferingJson;

    @Column(name = "product_offering_id")
    private String productOfferingId;

    @Column(name = "usage_spec_name")
    private String usageSpecName;

    @Column(name = "allowance_value")
    private BigDecimal allowanceValue;

    @Column(name = "units")
    private String units;

    @Column(name = "overage_price_value")
    private BigDecimal overagePriceValue;

    @Column(name = "overage_price_unit")
    private String overagePriceUnit;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** true = a one-time top-up: buying it boosts the buyer's current period. */
    @Column(name = "boost")
    private boolean boost;

    public UsageAllowance() {
    }

    public boolean isBoost() {
        return boost;
    }

    public void setBoost(boolean boost) {
        this.boost = boost;
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

    public String getProductOfferingJson() {
        return productOfferingJson;
    }

    public void setProductOfferingJson(String productOfferingJson) {
        this.productOfferingJson = productOfferingJson;
    }

    public String getProductOfferingId() {
        return productOfferingId;
    }

    public void setProductOfferingId(String productOfferingId) {
        this.productOfferingId = productOfferingId;
    }

    public String getUsageSpecName() {
        return usageSpecName;
    }

    public void setUsageSpecName(String usageSpecName) {
        this.usageSpecName = usageSpecName;
    }

    public BigDecimal getAllowanceValue() {
        return allowanceValue;
    }

    public void setAllowanceValue(BigDecimal allowanceValue) {
        this.allowanceValue = allowanceValue;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }

    public BigDecimal getOveragePriceValue() {
        return overagePriceValue;
    }

    public void setOveragePriceValue(BigDecimal overagePriceValue) {
        this.overagePriceValue = overagePriceValue;
    }

    public String getOveragePriceUnit() {
        return overagePriceUnit;
    }

    public void setOveragePriceUnit(String overagePriceUnit) {
        this.overagePriceUnit = overagePriceUnit;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
