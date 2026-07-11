package com.bss.intelligence.churn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The label: did this customer actually leave? Ground truth for training. */
@Entity
@Table(name = "churn_outcome")
public class ChurnOutcome {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "churned", nullable = false)
    private boolean churned;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public boolean isChurned() { return churned; }
    public void setChurned(boolean churned) { this.churned = churned; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
}
