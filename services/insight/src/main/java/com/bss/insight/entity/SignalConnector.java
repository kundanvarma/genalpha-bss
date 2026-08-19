package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One external signal source bound to a tenant. {@code kind} picks the
 * adapter ('servicedesk', 'http-webhook', …); {@code source} labels the
 * signals it delivers ('servicedesk', 'call', 'review'). Secrets are env-var
 * NAMES resolved at call time — never values.
 */
@Entity
@Table(name = "signal_connector")
public class SignalConnector {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String mode;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "secret_ref")
    private String secretRef;

    @Column(name = "webhook_secret_ref")
    private String webhookSecretRef;

    @Column(length = 2000)
    private String config;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String v) { this.secretRef = v; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public void setWebhookSecretRef(String v) { this.webhookSecretRef = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime v) { this.lastSyncAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
