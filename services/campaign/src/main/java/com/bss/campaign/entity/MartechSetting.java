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

    /** Quiet hours (tenant-local, "HH:mm", window may wrap midnight);
     * null start/end = no quiet hours. */
    @Column(name = "quiet_start", length = 5)
    private String quietStart;

    @Column(name = "quiet_end", length = 5)
    private String quietEnd;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public int getMaxMarketingMessages() { return maxMarketingMessages; }
    public void setMaxMarketingMessages(int v) { this.maxMarketingMessages = v; }
    public int getPerDays() { return perDays; }
    public void setPerDays(int v) { this.perDays = v; }
    public String getQuietStart() { return quietStart; }
    public void setQuietStart(String v) { this.quietStart = v; }
    public String getQuietEnd() { return quietEnd; }
    public void setQuietEnd(String v) { this.quietEnd = v; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String v) { this.timeZone = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
