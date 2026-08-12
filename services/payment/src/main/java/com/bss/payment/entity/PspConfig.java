package com.bss.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One PSP in a tenant's menu (several rows per tenant). Present rows opt the
 * tenant into per-tenant payment providers; none = the global env PSP. The API
 * key is never here — {@code secretRef} names an env var, read at call time.
 */
@Entity
@Table(name = "payment_provider_config")
public class PspConfig {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "secret_ref")
    private String secretRef;

    /** Env var naming the shared secret the PSP signs its webhooks with. */
    @Column(name = "webhook_secret_ref")
    private String webhookSecretRef;

    private String methods;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    /** Orchestration: lower is tried first among the matching card providers. */
    @Column(nullable = false)
    private int priority = 100;

    /** JSON array of ISO currency codes this provider handles; null/empty = any. */
    private String currencies;

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
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String v) { this.secretRef = v; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public void setWebhookSecretRef(String v) { this.webhookSecretRef = v; }
    public String getMethods() { return methods; }
    public void setMethods(String v) { this.methods = v; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean v) { this.isDefault = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public String getCurrencies() { return currencies; }
    public void setCurrencies(String v) { this.currencies = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
