package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The tenant's marketing-touch budget: max messages per N days, 0 = off.
 * A guardrail in the model, not the etiquette. */
@Entity
@Table(name = "martech_setting")
public class MartechSetting {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "max_marketing_messages", nullable = false)
    private int maxMarketingMessages;

    @Column(name = "per_days", nullable = false)
    private int perDays = 1;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public int getMaxMarketingMessages() { return maxMarketingMessages; }
    public void setMaxMarketingMessages(int v) { this.maxMarketingMessages = v; }
    public int getPerDays() { return perDays; }
    public void setPerDays(int v) { this.perDays = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
