package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** One thing a staff member did on a desk — an action, never a screen. The actor is a salted hash. */
@Entity
@Table(name = "desk_event")
public class DeskEvent {

    @Id
    private String id;
    @Column(name = "tenant_id")
    private String tenantId;
    @Column(name = "actor_hash")
    private String actorHash;
    @Column(name = "session_id")
    private String sessionId;
    private String desk;
    private String event;
    private String target;
    @Column(columnDefinition = "TEXT")
    private String props;
    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { tenantId = v; }
    public String getActorHash() { return actorHash; }
    public void setActorHash(String v) { actorHash = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v; }
    public String getDesk() { return desk; }
    public void setDesk(String v) { desk = v; }
    public String getEvent() { return event; }
    public void setEvent(String v) { event = v; }
    public String getTarget() { return target; }
    public void setTarget(String v) { target = v; }
    public String getProps() { return props; }
    public void setProps(String v) { props = v; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime v) { occurredAt = v; }
}
