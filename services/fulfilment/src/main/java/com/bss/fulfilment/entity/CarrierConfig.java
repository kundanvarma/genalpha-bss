package com.bss.fulfilment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One carrier in a tenant's menu (several rows per tenant). Present rows opt the
 * tenant into per-tenant carriers; none = the global env carrier. The API key is
 * never here — {@code secretRef} names an env var, read at call time.
 */
@Entity
@Table(name = "carrier_config")
public class CarrierConfig {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String carrier;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "secret_ref")
    private String secretRef;

    private String methods;

    private String config;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String v) { this.carrier = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String v) { this.secretRef = v; }
    public String getMethods() { return methods; }
    public void setMethods(String v) { this.methods = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean v) { this.isDefault = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
