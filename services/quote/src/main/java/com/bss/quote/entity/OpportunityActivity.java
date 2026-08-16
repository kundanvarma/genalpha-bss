package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A beat in the sales workspace: a call, an email, a note, a next step, or
 *  a lifecycle event (qualified, stage moved, won, lost). When the deal is
 *  with a known party these mirror onto that party's TMF683 timeline. */
@Entity
@Table(name = "opportunity_activity")
public class OpportunityActivity {

    // Author-logged kinds plus the auto-logged lifecycle beats.
    public static final String NOTE = "note";
    public static final String CALL = "call";
    public static final String EMAIL = "email";
    public static final String NEXT_STEP = "nextStep";
    public static final String LIFECYCLE = "lifecycle";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "opportunity_id", nullable = false, length = 36)
    private String opportunityId;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "activity_type", nullable = false, length = 32)
    private String activityType;

    @Column(name = "note", nullable = false, length = 2000)
    private String note;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOpportunityId() { return opportunityId; }
    public void setOpportunityId(String v) { this.opportunityId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String v) { this.activityType = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime v) { this.occurredAt = v; }
}
