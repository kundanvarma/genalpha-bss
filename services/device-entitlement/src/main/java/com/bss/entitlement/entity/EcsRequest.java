package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One device request at the ECS door, as the operator sees it: which
 * device, which SIM, which app and operation, what we answered and why. */
@Entity
@Table(name = "ecs_request")
public class EcsRequest {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    @Column(length = 16)
    private String imsi;

    @Column(length = 64)
    private String app;

    @Column(length = 48)
    private String operation;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String v) { this.terminalId = v; }
    public String getImsi() { return imsi; }
    public void setImsi(String v) { this.imsi = v; }
    public String getApp() { return app; }
    public void setApp(String v) { this.app = v; }
    public String getOperation() { return operation; }
    public void setOperation(String v) { this.operation = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
