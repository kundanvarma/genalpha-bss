package com.bss.appointment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "appointment")
public class Appointment {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "status")
    private String status;

    @Column(name = "description")
    private String description;

    @Column(name = "start_at")
    private OffsetDateTime startAt;

    @Column(name = "end_at")
    private OffsetDateTime endAt;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "related_entity")
    private String relatedEntityJson;

    @Column(name = "place")
    private String placeJson;

    @Column(name = "creation_date")
    private OffsetDateTime creationDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public Appointment() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OffsetDateTime getStartAt() {
        return startAt;
    }

    public void setStartAt(OffsetDateTime startAt) {
        this.startAt = startAt;
    }

    public OffsetDateTime getEndAt() {
        return endAt;
    }

    public void setEndAt(OffsetDateTime endAt) {
        this.endAt = endAt;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getRelatedEntityJson() {
        return relatedEntityJson;
    }

    public void setRelatedEntityJson(String relatedEntityJson) {
        this.relatedEntityJson = relatedEntityJson;
    }

    public String getPlaceJson() {
        return placeJson;
    }

    public void setPlaceJson(String placeJson) {
        this.placeJson = placeJson;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
