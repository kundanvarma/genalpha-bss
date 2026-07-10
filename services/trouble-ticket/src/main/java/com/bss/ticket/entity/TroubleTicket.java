package com.bss.ticket.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "trouble_ticket")
public class TroubleTicket {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String IN_PROGRESS = "inProgress";
    public static final String RESOLVED = "resolved";
    public static final String CLOSED = "closed";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "severity")
    private String severity;

    @Column(name = "status")
    private String status;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "related_entity")
    private String relatedEntityJson;

    @Column(name = "note")
    private String noteJson;

    @Column(name = "creation_date")
    private OffsetDateTime creationDate;

    @Column(name = "status_change_date")
    private OffsetDateTime statusChangeDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public TroubleTicket() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getRelatedEntityJson() {
        return relatedEntityJson;
    }

    public void setRelatedEntityJson(String relatedEntityJson) {
        this.relatedEntityJson = relatedEntityJson;
    }

    public String getNoteJson() {
        return noteJson;
    }

    public void setNoteJson(String noteJson) {
        this.noteJson = noteJson;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public OffsetDateTime getStatusChangeDate() {
        return statusChangeDate;
    }

    public void setStatusChangeDate(OffsetDateTime statusChangeDate) {
        this.statusChangeDate = statusChangeDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
