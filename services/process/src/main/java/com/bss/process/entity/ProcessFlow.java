package com.bss.process.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One run of a spec, projected from the event stream. TMF701 run-time half. */
@Entity
@Table(name = "process_flow")
public class ProcessFlow {

    public static final String IN_PROGRESS = "inProgress";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "spec_code")
    private String specCode;

    /** The productOrder id — the one correlation key everything joins on. */
    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "party_id")
    private String partyId;

    private String state;

    private String message;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSpecCode() { return specCode; }
    public void setSpecCode(String v) { this.specCode = v; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
