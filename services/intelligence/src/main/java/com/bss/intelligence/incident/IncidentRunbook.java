package com.bss.intelligence.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A promoted lesson: versioned, provenanced, revocable. */
@Entity
@Table(name = "incident_runbook")
public class IncidentRunbook {

    public static final String PROPOSED = "proposed";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String REVOKED = "revoked";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String signature;

    private int version;

    private String status;

    private String title;

    private String diagnosis;

    private String action;

    @Column(name = "provenance_json")
    private String provenanceJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_note")
    private String decidedNote;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSignature() { return signature; }
    public void setSignature(String v) { this.signature = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String v) { this.diagnosis = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getProvenanceJson() { return provenanceJson; }
    public void setProvenanceJson(String v) { this.provenanceJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime v) { this.decidedAt = v; }
    public String getDecidedNote() { return decidedNote; }
    public void setDecidedNote(String v) { this.decidedNote = v; }
}
