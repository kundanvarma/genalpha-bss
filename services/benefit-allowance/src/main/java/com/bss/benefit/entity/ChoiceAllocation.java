package com.bss.benefit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Choices a benefit took out of the pool — one per order item that picked it; held until the order completes, then committed. */
@Entity
@Table(name = "choice_allocation")
public class ChoiceAllocation {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "pool_id", nullable = false)
    private String poolId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "order_item_id", nullable = false)
    private String orderItemId;

    @Column(name = "offering_id", nullable = false)
    private String offeringId;

    @Column(name = "offering_name", nullable = false)
    private String offeringName;

    @Column(name = "cost", nullable = false)
    private int cost;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    /** Why it ended: swapped or released (null while held or committed). */
    @Column(name = "end_reason")
    private String endReason;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    /** The benefit's own hold, as picked (a snapshot: a later catalog change does not rewrite it). */
    @Column(name = "hold_months")
    private Integer holdMonths;

    /** Until when the Choice is held: release and swap are refused before it. */
    @Column(name = "held_until")
    private OffsetDateTime heldUntil;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(String orderItemId) {
        this.orderItemId = orderItemId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public void setOfferingId(String offeringId) {
        this.offeringId = offeringId;
    }

    public String getOfferingName() {
        return offeringName;
    }

    public void setOfferingName(String offeringName) {
        this.offeringName = offeringName;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Integer getHoldMonths() {
        return holdMonths;
    }

    public void setHoldMonths(Integer holdMonths) {
        this.holdMonths = holdMonths;
    }

    public OffsetDateTime getHeldUntil() {
        return heldUntil;
    }

    public void setHeldUntil(OffsetDateTime heldUntil) {
        this.heldUntil = heldUntil;
    }
}
