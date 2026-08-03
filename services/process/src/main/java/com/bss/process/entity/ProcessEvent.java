package com.bss.process.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One correlated event on a flow's timeline — the journal Live Flow never keeps. */
@Entity
@Table(name = "process_event")
public class ProcessEvent {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "process_flow_id")
    private String processFlowId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "source_topic")
    private String sourceTopic;

    @Column(name = "event_time")
    private OffsetDateTime eventTime;

    private String digest;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getProcessFlowId() { return processFlowId; }
    public void setProcessFlowId(String v) { this.processFlowId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getSourceTopic() { return sourceTopic; }
    public void setSourceTopic(String v) { this.sourceTopic = v; }
    public OffsetDateTime getEventTime() { return eventTime; }
    public void setEventTime(OffsetDateTime v) { this.eventTime = v; }
    public String getDigest() { return digest; }
    public void setDigest(String v) { this.digest = v; }
}
