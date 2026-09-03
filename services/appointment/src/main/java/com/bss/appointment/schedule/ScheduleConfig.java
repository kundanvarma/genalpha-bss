package com.bss.appointment.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One row per tenant: when installers work and how many visits a window holds by default. */
@Entity
@Table(name = "schedule_config")
public class ScheduleConfig {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "working_days", nullable = false, length = 64)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    @Column(name = "slot_starts", nullable = false)
    private String slotStarts = "09:00,11:00,13:00,15:00";

    @Column(name = "slot_hours", nullable = false)
    private int slotHours = 2;

    @Column(name = "days_ahead", nullable = false)
    private int daysAhead = 7;

    @Column(name = "default_capacity", nullable = false)
    private int defaultCapacity = 3;

    /** Who answers the calendar: 'roster' (built-in) or 'tmf646' (the tenant's own workforce system). */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider = "roster";

    @Column(name = "provider_url", length = 500)
    private String providerUrl;

    /** Name of the ENV VAR holding the provider credential — never the secret itself. */
    @Column(name = "provider_secret_ref", length = 128)
    private String providerSecretRef;

    /** The provider's capacity category / work-skill group for installs (e.g. "fibre-install"). */
    @Column(name = "provider_category", length = 64)
    private String providerCategory;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String v) { this.workingDays = v; }
    public String getSlotStarts() { return slotStarts; }
    public void setSlotStarts(String v) { this.slotStarts = v; }
    public int getSlotHours() { return slotHours; }
    public void setSlotHours(int v) { this.slotHours = v; }
    public int getDaysAhead() { return daysAhead; }
    public void setDaysAhead(int v) { this.daysAhead = v; }
    public int getDefaultCapacity() { return defaultCapacity; }
    public void setDefaultCapacity(int v) { this.defaultCapacity = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getProviderUrl() { return providerUrl; }
    public void setProviderUrl(String v) { this.providerUrl = v; }
    public String getProviderSecretRef() { return providerSecretRef; }
    public void setProviderSecretRef(String v) { this.providerSecretRef = v; }
    public String getProviderCategory() { return providerCategory; }
    public void setProviderCategory(String v) { this.providerCategory = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
