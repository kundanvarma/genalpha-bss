package com.bss.agreement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "agreement")
public class Agreement {

    public static final String IN_PROCESS = "inProcess";
    public static final String ACTIVE = "active";
    public static final String TERMINATED = "terminated";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "agreement_type", length = 64)
    private String agreementType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "period_start")
    private OffsetDateTime periodStart;

    @Column(name = "period_end")
    private OffsetDateTime periodEnd;

    @Column(name = "commitment_months")
    private Integer commitmentMonths;

    /** JSON list of engaged party refs, echoed verbatim. */
    @Column(name = "engaged_party", length = 2000)
    private String engagedPartyJson;

    /** JSON list of agreement items (product/offering refs), echoed verbatim. */
    @Column(name = "agreement_item", length = 4000)
    private String agreementItemJson;

    /** JSON list of characteristics, echoed verbatim. */
    @Column(name = "characteristic", length = 2000)
    private String characteristicJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAgreementType() { return agreementType; }
    public void setAgreementType(String agreementType) { this.agreementType = agreementType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(OffsetDateTime periodStart) { this.periodStart = periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(OffsetDateTime periodEnd) { this.periodEnd = periodEnd; }
    public Integer getCommitmentMonths() { return commitmentMonths; }
    public void setCommitmentMonths(Integer commitmentMonths) { this.commitmentMonths = commitmentMonths; }
    public String getEngagedPartyJson() { return engagedPartyJson; }
    public void setEngagedPartyJson(String engagedPartyJson) { this.engagedPartyJson = engagedPartyJson; }
    public String getAgreementItemJson() { return agreementItemJson; }
    public void setAgreementItemJson(String agreementItemJson) { this.agreementItemJson = agreementItemJson; }
    public String getCharacteristicJson() { return characteristicJson; }
    public void setCharacteristicJson(String characteristicJson) { this.characteristicJson = characteristicJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
