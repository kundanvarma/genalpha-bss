package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One diagnostic run, kept: the verdict and every finding. */
@Entity
@Table(name = "service_test")
public class ServiceTest {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "service_id")
    private String serviceId;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    private String verdict;

    private String name;

    @Column(name = "test_spec_json")
    private String testSpecJson;

    @Column(name = "findings_json")
    private String findingsJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { this.serviceId = v; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String v) { this.ownerPartyId = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getTestSpecJson() { return testSpecJson; }
    public void setTestSpecJson(String v) { this.testSpecJson = v; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String v) { this.findingsJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
