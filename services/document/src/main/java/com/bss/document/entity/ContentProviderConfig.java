package com.bss.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A tenant's external CMS/DAM binding. Present = this tenant serves its
 * imagery FROM that headless CMS (reference mode); absent = the built-in
 * hosted store. The token itself is never here — {@code secretRef} names an
 * environment variable the provider reads at call time.
 */
@Entity
@Table(name = "content_provider_config")
public class ContentProviderConfig {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "project_id", length = 128)
    private String projectId;

    @Column(name = "dataset", length = 128)
    private String dataset;

    @Column(name = "secret_ref", length = 128)
    private String secretRef;

    /** Env var naming the shared secret the CMS signs its webhooks with. */
    @Column(name = "webhook_secret_ref", length = 128)
    private String webhookSecretRef;

    @Column(name = "direct_url", nullable = false)
    private boolean directUrl;

    @Column(name = "config", length = 2000)
    private String config;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public void setWebhookSecretRef(String webhookSecretRef) { this.webhookSecretRef = webhookSecretRef; }
    public boolean isDirectUrl() { return directUrl; }
    public void setDirectUrl(boolean directUrl) { this.directUrl = directUrl; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
