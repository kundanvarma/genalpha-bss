package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One bill split into N monthly parts — the last part takes the
 * rounding remainder, so the parts always sum to the bill. */
@Entity
@Table(name = "installment_plan")
public class InstallmentPlan {

    public static final String ACTIVE = "active";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String BROKEN = "broken";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "bill_id", nullable = false, length = 36)
    private String billId;

    @Column(name = "installments", nullable = false)
    private int installments;

    @Column(name = "amount_per", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPer;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "paid_count", nullable = false)
    private int paidCount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "next_due_at")
    private OffsetDateTime nextDueAt;

    /** When the ONE overdue reminder went out (null = not reminded). */
    @Column(name = "reminded_at")
    private OffsetDateTime remindedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getBillId() { return billId; }
    public void setBillId(String v) { this.billId = v; }
    public int getInstallments() { return installments; }
    public void setInstallments(int v) { this.installments = v; }
    public BigDecimal getAmountPer() { return amountPer; }
    public void setAmountPer(BigDecimal v) { this.amountPer = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public int getPaidCount() { return paidCount; }
    public void setPaidCount(int v) { this.paidCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getNextDueAt() { return nextDueAt; }
    public void setNextDueAt(OffsetDateTime v) { this.nextDueAt = v; }
    public OffsetDateTime getRemindedAt() { return remindedAt; }
    public void setRemindedAt(OffsetDateTime v) { this.remindedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }

    /** What is still owed: total minus the equal parts already paid. */
    public BigDecimal remainingOf(BigDecimal total) {
        return total.subtract(amountPer.multiply(BigDecimal.valueOf(paidCount)));
    }

    /** What this particular part costs: the last one takes the remainder. */
    public BigDecimal amountOf(int index, BigDecimal total) {
        if (index < installments - 1) {
            return amountPer;
        }
        return total.subtract(amountPer.multiply(BigDecimal.valueOf(installments - 1)));
    }
}
