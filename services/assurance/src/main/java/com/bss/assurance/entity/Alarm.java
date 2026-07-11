package com.bss.assurance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "alarm")
public class Alarm {

    public static final String RAISED = "raised";
    public static final String CLEARED = "cleared";
    public static final String CRITICAL = "critical";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "alarmed_object", nullable = false, length = 128)
    private String alarmedObject;

    @Column(name = "alarm_type", length = 64)
    private String alarmType;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "probable_cause")
    private String probableCause;

    @Column(name = "raised_at", nullable = false)
    private OffsetDateTime raisedAt;

    @Column(name = "cleared_at")
    private OffsetDateTime clearedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getAlarmedObject() { return alarmedObject; }
    public void setAlarmedObject(String alarmedObject) { this.alarmedObject = alarmedObject; }
    public String getAlarmType() { return alarmType; }
    public void setAlarmType(String alarmType) { this.alarmType = alarmType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getProbableCause() { return probableCause; }
    public void setProbableCause(String probableCause) { this.probableCause = probableCause; }
    public OffsetDateTime getRaisedAt() { return raisedAt; }
    public void setRaisedAt(OffsetDateTime raisedAt) { this.raisedAt = raisedAt; }
    public OffsetDateTime getClearedAt() { return clearedAt; }
    public void setClearedAt(OffsetDateTime clearedAt) { this.clearedAt = clearedAt; }
}
