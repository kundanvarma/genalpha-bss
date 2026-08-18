package com.bss.address.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The GDPR ledger row: one registry lookup — who asked, about whom, for what
 * purpose, against which country's register, and the outcome. The registry's
 * answer body is deliberately NOT stored; the outcome word is the whole record.
 */
@Entity
@Table(name = "registry_lookup_log")
public class RegistryLookupLog {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private String provider;

    @Column(name = "caller_sub")
    private String callerSub;

    @Column(name = "party_name")
    private String partyName;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getCallerSub() { return callerSub; }
    public void setCallerSub(String v) { this.callerSub = v; }
    public String getPartyName() { return partyName; }
    public void setPartyName(String v) { this.partyName = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
