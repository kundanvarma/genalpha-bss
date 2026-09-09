package com.bss.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One line of the launch-governance trail: who did what to which offering, and which envelope (if any) decided it. */
@Entity
@Table(name = "governance_ledger")
public class GovernanceLedger {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "offering_id", nullable = false, length = 36)
    private String offeringId;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "actor", length = 200)
    private String actor;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "envelope_id", length = 36)
    private String envelopeId;

    @Column(name = "envelope_name", length = 200)
    private String envelopeName;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String offeringId) { this.offeringId = offeringId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getEnvelopeId() { return envelopeId; }
    public void setEnvelopeId(String envelopeId) { this.envelopeId = envelopeId; }
    public String getEnvelopeName() { return envelopeName; }
    public void setEnvelopeName(String envelopeName) { this.envelopeName = envelopeName; }
    public OffsetDateTime getAt() { return at; }
    public void setAt(OffsetDateTime at) { this.at = at; }
}
