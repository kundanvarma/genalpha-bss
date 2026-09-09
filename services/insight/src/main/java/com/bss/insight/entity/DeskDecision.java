package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** A suggestion someone accepted or dismissed; dismissed ones stay quiet. */
@Entity
@Table(name = "desk_decision")
public class DeskDecision {

    @Id
    private String id;
    @Column(name = "tenant_id")
    private String tenantId;
    @Column(name = "suggestion_id")
    private String suggestionId;
    private String decision;
    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { tenantId = v; }
    public String getSuggestionId() { return suggestionId; }
    public void setSuggestionId(String v) { suggestionId = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { decision = v; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime v) { decidedAt = v; }
}
