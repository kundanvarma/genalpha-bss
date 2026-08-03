package com.bss.fulfilment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF697: the installer's visit, born from the booked appointment. */
@Entity
@Table(name = "work_order")
public class WorkOrder {

    public static final String PLANNED = "planned";
    public static final String IN_PROGRESS = "inProgress";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "appointment_id")
    private String appointmentId;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    private String state;

    @Column(name = "place_json")
    private String placeJson;

    private String note;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public String getAppointmentId() { return appointmentId; }
    public void setAppointmentId(String v) { this.appointmentId = v; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String v) { this.ownerPartyId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getPlaceJson() { return placeJson; }
    public void setPlaceJson(String v) { this.placeJson = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
