package com.bss.loyalty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One point movement, forever: every earn and burn keeps its cause. */
@Entity
@Table(name = "loyalty_transaction")
public class LoyaltyTransaction {

    public static final String EARN = "earn";
    public static final String BURN = "burn";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "party_id", nullable = false)
    private String partyId;

    @Column(name = "tx_type", nullable = false)
    private String txType;

    @Column(nullable = false)
    private long points;

    @Column(nullable = false)
    private String cause;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getTxType() { return txType; }
    public void setTxType(String v) { this.txType = v; }
    public long getPoints() { return points; }
    public void setPoints(long v) { this.points = v; }
    public String getCause() { return cause; }
    public void setCause(String v) { this.cause = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
