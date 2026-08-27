package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The directory-services obligation, as data: per party (+ optional
 * serviceRef when the choice is per subscription) — exposure
 * full | partial | reserved, and the mandatory free secret-number service
 * (forces reserved, suppresses everything, CLIR implied; the number is
 * never recycled — a number-inventory interaction noted for the future).
 */
@Entity
@Table(name = "directory_setting")
public class DirectorySetting {

    public static final String EXPOSURE_FULL = "full";
    public static final String EXPOSURE_PARTIAL = "partial";
    public static final String EXPOSURE_RESERVED = "reserved";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 36)
    private String partyId;

    @Column(name = "service_ref", length = 64)
    private String serviceRef;

    @Column(name = "exposure", nullable = false, length = 16)
    private String exposure;

    @Column(name = "secret_number", nullable = false)
    private boolean secretNumber;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getServiceRef() { return serviceRef; }
    public void setServiceRef(String serviceRef) { this.serviceRef = serviceRef; }
    public String getExposure() { return exposure; }
    public void setExposure(String exposure) { this.exposure = exposure; }
    public boolean isSecretNumber() { return secretNumber; }
    public void setSecretNumber(boolean secretNumber) { this.secretNumber = secretNumber; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
