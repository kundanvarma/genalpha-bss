package com.bss.intelligence.churn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One customer's feature vector on one day — tomorrow's training row. */
@Entity
@Table(name = "churn_feature_snapshot")
public class ChurnFeatureSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "days_to_commitment_end", nullable = false, precision = 8, scale = 1)
    private BigDecimal daysToCommitmentEnd;

    @Column(name = "max_usage_ratio", nullable = false, precision = 6, scale = 3)
    private BigDecimal maxUsageRatio;

    @Column(name = "tickets_last_30d", nullable = false, precision = 5, scale = 0)
    private BigDecimal ticketsLast30d;

    @Column(name = "open_ticket_during_outage", nullable = false, precision = 1, scale = 0)
    private BigDecimal openTicketDuringOutage;

    @Column(name = "taken_at", nullable = false)
    private OffsetDateTime takenAt;

    public double[] featureVector() {
        return new double[] {
                daysToCommitmentEnd.doubleValue(),
                maxUsageRatio.doubleValue(),
                ticketsLast30d.doubleValue(),
                openTicketDuringOutage.doubleValue(),
        };
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public BigDecimal getDaysToCommitmentEnd() { return daysToCommitmentEnd; }
    public void setDaysToCommitmentEnd(BigDecimal v) { this.daysToCommitmentEnd = v; }
    public BigDecimal getMaxUsageRatio() { return maxUsageRatio; }
    public void setMaxUsageRatio(BigDecimal v) { this.maxUsageRatio = v; }
    public BigDecimal getTicketsLast30d() { return ticketsLast30d; }
    public void setTicketsLast30d(BigDecimal v) { this.ticketsLast30d = v; }
    public BigDecimal getOpenTicketDuringOutage() { return openTicketDuringOutage; }
    public void setOpenTicketDuringOutage(BigDecimal v) { this.openTicketDuringOutage = v; }
    public OffsetDateTime getTakenAt() { return takenAt; }
    public void setTakenAt(OffsetDateTime takenAt) { this.takenAt = takenAt; }
}
