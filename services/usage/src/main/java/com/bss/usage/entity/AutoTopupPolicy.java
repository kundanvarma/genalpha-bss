package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Opt-in auto top-up: when the OCS reports a threshold breach, buy the named
 * boost — never more than the per-cycle caps, never without consent_at.
 */
@Entity
@Table(name = "auto_topup_policy")
public class AutoTopupPolicy {

    public static final String TRIGGER_DEPLETION = "depletion";
    public static final String TRIGGER_THRESHOLD = "thresholdPct";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "party_id")
    private String partyId;

    private boolean enabled;

    @Column(name = "boost_offering_id")
    private String boostOfferingId;

    @Column(name = "trigger_type")
    private String triggerType;

    @Column(name = "trigger_pct")
    private BigDecimal triggerPct;

    @Column(name = "max_boosts_per_cycle")
    private int maxBoostsPerCycle;

    @Column(name = "max_spend_per_cycle")
    private BigDecimal maxSpendPerCycle;

    @Column(name = "consent_at")
    private OffsetDateTime consentAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBoostOfferingId() { return boostOfferingId; }
    public void setBoostOfferingId(String boostOfferingId) { this.boostOfferingId = boostOfferingId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public BigDecimal getTriggerPct() { return triggerPct; }
    public void setTriggerPct(BigDecimal triggerPct) { this.triggerPct = triggerPct; }
    public int getMaxBoostsPerCycle() { return maxBoostsPerCycle; }
    public void setMaxBoostsPerCycle(int maxBoostsPerCycle) { this.maxBoostsPerCycle = maxBoostsPerCycle; }
    public BigDecimal getMaxSpendPerCycle() { return maxSpendPerCycle; }
    public void setMaxSpendPerCycle(BigDecimal maxSpendPerCycle) { this.maxSpendPerCycle = maxSpendPerCycle; }
    public OffsetDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(OffsetDateTime consentAt) { this.consentAt = consentAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
