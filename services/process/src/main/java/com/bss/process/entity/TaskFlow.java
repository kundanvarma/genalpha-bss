package com.bss.process.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A step of a flow: what it owes, whether it delivered, or went stuck. */
@Entity
@Table(name = "task_flow")
public class TaskFlow {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "inProgress";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String HELD = "held";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "process_flow_id")
    private String processFlowId;

    private String code;

    private String name;

    private int seq;

    private String state;

    @Column(name = "allowance_seconds")
    private Long allowanceSeconds;

    private String message;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getProcessFlowId() { return processFlowId; }
    public void setProcessFlowId(String v) { this.processFlowId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public int getSeq() { return seq; }
    public void setSeq(int v) { this.seq = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public Long getAllowanceSeconds() { return allowanceSeconds; }
    public void setAllowanceSeconds(Long v) { this.allowanceSeconds = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
}
