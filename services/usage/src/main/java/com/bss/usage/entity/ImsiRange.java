package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** An IMSI range the host MNO lends the MVNO — a modeled pool resource (like the
 *  MSISDN pool), not real SIM personalisation. */
@Entity
@Table(name = "imsi_range")
public class ImsiRange {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "host_party_id", length = 64)
    private String hostPartyId;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "prefix", length = 32)
    private String prefix;

    @Column(name = "from_imsi", nullable = false, length = 32)
    private String fromImsi;

    @Column(name = "to_imsi", nullable = false, length = 32)
    private String toImsi;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "note")
    private String note;

    @Column(name = "allocated_at")
    private OffsetDateTime allocatedAt;

    public ImsiRange() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getHostPartyId() { return hostPartyId; }
    public void setHostPartyId(String hostPartyId) { this.hostPartyId = hostPartyId; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getFromImsi() { return fromImsi; }
    public void setFromImsi(String fromImsi) { this.fromImsi = fromImsi; }
    public String getToImsi() { return toImsi; }
    public void setToImsi(String toImsi) { this.toImsi = toImsi; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getAllocatedAt() { return allocatedAt; }
    public void setAllocatedAt(OffsetDateTime allocatedAt) { this.allocatedAt = allocatedAt; }
}
