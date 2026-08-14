package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The receipt for every next-best-action decision: when two journeys would
 * message the same customer in the same tick, one wins and the other is held.
 * Every choice is logged with its reason — the NBA, explainable.
 */
@Entity
@Table(name = "arbitration_decision")
public class ArbitrationDecision {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "winner_journey_id")
    private String winnerJourneyId;

    @Column(name = "held_journey_id")
    private String heldJourneyId;

    @Column(length = 500)
    private String reason;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getWinnerJourneyId() { return winnerJourneyId; }
    public void setWinnerJourneyId(String v) { this.winnerJourneyId = v; }
    public String getHeldJourneyId() { return heldJourneyId; }
    public void setHeldJourneyId(String v) { this.heldJourneyId = v; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime v) { this.decidedAt = v; }
}
