package com.bss.communication.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A customer's marketing opt-out — the self-serve preference behind the law.
 * Party-keyed (the person), so the send path (which works in party ids) can skip
 * them directly, for in-app AND email. Distinct from the email {@link Suppression}
 * ledger (bounces/complaints, address-keyed): this one is the customer's own
 * choice. Presence of a row = opted OUT; opting back in deletes it.
 */
@Entity
@Table(name = "marketing_opt_out")
public class MarketingOptOut {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
