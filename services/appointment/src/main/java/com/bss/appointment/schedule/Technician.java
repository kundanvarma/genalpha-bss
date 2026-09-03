package com.bss.appointment.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A field installer: who they are, where they go, and when they work. */
@Entity
@Table(name = "technician")
public class Technician {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "skills")
    private String skills;

    @Column(name = "zone")
    private String zone;

    @Column(name = "working_days", nullable = false, length = 64)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    @Column(name = "start_time", nullable = false, length = 5)
    private String startTime = "08:00";

    @Column(name = "end_time", nullable = false, length = 5)
    private String endTime = "17:00";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "creation_date")
    private OffsetDateTime creationDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getSkills() { return skills; }
    public void setSkills(String v) { this.skills = v; }
    public String getZone() { return zone; }
    public void setZone(String v) { this.zone = v; }
    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String v) { this.workingDays = v; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String v) { this.startTime = v; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String v) { this.endTime = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreationDate() { return creationDate; }
    public void setCreationDate(OffsetDateTime v) { this.creationDate = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
