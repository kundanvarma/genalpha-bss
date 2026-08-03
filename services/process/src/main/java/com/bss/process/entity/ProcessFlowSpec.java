package com.bss.process.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Design intent as DATA: what a flow's tasks owe, and by when. */
@Entity
@Table(name = "process_flow_spec")
@IdClass(ProcessFlowSpec.Key.class)
public class ProcessFlowSpec {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Id
    private String code;

    private String name;

    private String description;

    /** [{code, name, seq, owedEvent, allowanceSeconds}] */
    @Column(name = "tasks_json")
    private String tasksJson;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getTasksJson() { return tasksJson; }
    public void setTasksJson(String v) { this.tasksJson = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }

    public static class Key implements Serializable {
        private String tenantId;
        private String code;
        public Key() { }
        public Key(String tenantId, String code) { this.tenantId = tenantId; this.code = code; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(code, k.code);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, code); }
    }
}
