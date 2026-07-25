package com.bss.loyalty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** An opt-in member: the party IS the id; the balance is the ledger's sum. */
@Entity
@Table(name = "loyalty_member")
@IdClass(LoyaltyMember.Key.class)
public class LoyaltyMember {

    @Id
    private String id;

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public long getBalance() { return balance; }
    public void setBalance(long v) { this.balance = v; }
    public OffsetDateTime getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(OffsetDateTime v) { this.enrolledAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }

    public static class Key implements Serializable {
        public String id;
        public String tenantId;
        public Key() { }
        public Key(String id, String tenantId) { this.id = id; this.tenantId = tenantId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(id, k.id) && Objects.equals(tenantId, k.tenantId);
        }
        @Override public int hashCode() { return Objects.hash(id, tenantId); }
    }
}
