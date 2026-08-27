package com.bss.basemigration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One subscriber's journey through a migration plan: scheduled → noticed /
 * exit-window → order-emitted → migrated | exited | failed | rolled-back.
 * Carries the pre-migration product snapshot so a rollback can emit the
 * exact inverse modify order.
 */
@Entity
@Table(name = "migration_customer")
public class MigrationCustomer {

    public static final String SCHEDULED = "scheduled";
    public static final String NOTICED = "noticed";
    public static final String EXIT_WINDOW = "exit-window";
    public static final String ORDER_EMITTED = "order-emitted";
    public static final String MIGRATED = "migrated";
    public static final String EXITED = "exited";
    public static final String FAILED = "failed";
    public static final String ROLLED_BACK = "rolled-back";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "plan_id", nullable = false, length = 36)
    private String planId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "source_offering_id", length = 64)
    private String sourceOfferingId;

    @Column(name = "source_offering_name")
    private String sourceOfferingName;

    @Column(name = "target_offering_id", length = 64)
    private String targetOfferingId;

    @Column(name = "target_offering_name")
    private String targetOfferingName;

    @Column(name = "delta_class", length = 16)
    private String deltaClass;

    @Column(name = "state", nullable = false, length = 24)
    private String state;

    @Column(name = "scheduled_for")
    private OffsetDateTime scheduledFor;

    @Column(name = "notice_sent_at")
    private OffsetDateTime noticeSentAt;

    @Column(name = "order_ref", length = 64)
    private String orderRef;

    @Column(name = "rollback_order_ref", length = 64)
    private String rollbackOrderRef;

    @Column(name = "snapshot_json", length = 8000)
    private String snapshotJson;

    @Column(name = "penalty_free_exit", nullable = false)
    private boolean penaltyFreeExit;

    @Column(name = "exit_right", nullable = false)
    private boolean exitRight;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getSourceOfferingId() { return sourceOfferingId; }
    public void setSourceOfferingId(String sourceOfferingId) { this.sourceOfferingId = sourceOfferingId; }
    public String getSourceOfferingName() { return sourceOfferingName; }
    public void setSourceOfferingName(String sourceOfferingName) { this.sourceOfferingName = sourceOfferingName; }
    public String getTargetOfferingId() { return targetOfferingId; }
    public void setTargetOfferingId(String targetOfferingId) { this.targetOfferingId = targetOfferingId; }
    public String getTargetOfferingName() { return targetOfferingName; }
    public void setTargetOfferingName(String targetOfferingName) { this.targetOfferingName = targetOfferingName; }
    public String getDeltaClass() { return deltaClass; }
    public void setDeltaClass(String deltaClass) { this.deltaClass = deltaClass; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public OffsetDateTime getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(OffsetDateTime scheduledFor) { this.scheduledFor = scheduledFor; }
    public OffsetDateTime getNoticeSentAt() { return noticeSentAt; }
    public void setNoticeSentAt(OffsetDateTime noticeSentAt) { this.noticeSentAt = noticeSentAt; }
    public String getOrderRef() { return orderRef; }
    public void setOrderRef(String orderRef) { this.orderRef = orderRef; }
    public String getRollbackOrderRef() { return rollbackOrderRef; }
    public void setRollbackOrderRef(String rollbackOrderRef) { this.rollbackOrderRef = rollbackOrderRef; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public boolean isPenaltyFreeExit() { return penaltyFreeExit; }
    public void setPenaltyFreeExit(boolean penaltyFreeExit) { this.penaltyFreeExit = penaltyFreeExit; }
    public boolean isExitRight() { return exitRight; }
    public void setExitRight(boolean exitRight) { this.exitRight = exitRight; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
