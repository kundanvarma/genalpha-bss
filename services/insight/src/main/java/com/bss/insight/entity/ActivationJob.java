package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A background audience-activation export (queued → running → done|error). */
@Entity
@Table(name = "activation_job")
public class ActivationJob {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "audience_id")
    private String audienceId;

    @Column(name = "external_audience_id")
    private String externalAudienceId;

    @Column(name = "mode")
    private String mode;

    @Column(name = "status")
    private String status;

    @Column(name = "members")
    private Integer members;

    @Column(name = "pushed")
    private Integer pushed;

    @Column(name = "skipped")
    private Integer skipped;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAudienceId() { return audienceId; }
    public void setAudienceId(String audienceId) { this.audienceId = audienceId; }
    public String getExternalAudienceId() { return externalAudienceId; }
    public void setExternalAudienceId(String externalAudienceId) { this.externalAudienceId = externalAudienceId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getMembers() { return members; }
    public void setMembers(Integer members) { this.members = members; }
    public Integer getPushed() { return pushed; }
    public void setPushed(Integer pushed) { this.pushed = pushed; }
    public Integer getSkipped() { return skipped; }
    public void setSkipped(Integer skipped) { this.skipped = skipped; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
