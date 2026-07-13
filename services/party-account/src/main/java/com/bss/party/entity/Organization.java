package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization")
public class Organization {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name")
    private String name;

    @Column(name = "trading_name")
    private String tradingName;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @jakarta.persistence.Column(name = "device_allowance_value")
    private java.math.BigDecimal deviceAllowanceValue;

    @jakarta.persistence.Column(name = "device_allowance_unit")
    private String deviceAllowanceUnit;

    public Organization() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTradingName() {
        return tradingName;
    }

    public void setTradingName(String tradingName) {
        this.tradingName = tradingName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public java.math.BigDecimal getDeviceAllowanceValue() { return deviceAllowanceValue; }
    public void setDeviceAllowanceValue(java.math.BigDecimal v) { this.deviceAllowanceValue = v; }
    public String getDeviceAllowanceUnit() { return deviceAllowanceUnit; }
    public void setDeviceAllowanceUnit(String u) { this.deviceAllowanceUnit = u; }
}
