package com.bss.ordering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The stored credit DECISION — never the report (see V10 migration note). */
@Entity
@Table(name = "credit_decision")
public class CreditDecision {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 36)
    private String partyId;

    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    @Column(name = "score_band", length = 16)
    private String scoreBand;

    @Column(name = "remarks_present", nullable = false)
    private boolean remarksPresent;

    @Column(name = "purpose", length = 64)
    private String purpose;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt;

    /** The order the decision was taken for (null on decisions stored before #422). */
    @Column(name = "order_id", length = 36)
    private String orderId;

    /** Why, as a short machine code (see CreditReason). */
    @Column(name = "reason_code", length = 32)
    private String reasonCode;

    /** Why, as the plain-language sentence the customer is shown. */
    @Column(name = "reason", length = 400)
    private String reason;

    /** When the customer asked a person to review this decision; null until they do. */
    @Column(name = "review_requested_at")
    private OffsetDateTime reviewRequestedAt;

    /** The TMF621 ticket that carries the review to a person. */
    @Column(name = "review_ticket_id", length = 64)
    private String reviewTicketId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getScoreBand() { return scoreBand; }
    public void setScoreBand(String scoreBand) { this.scoreBand = scoreBand; }
    public boolean isRemarksPresent() { return remarksPresent; }
    public void setRemarksPresent(boolean remarksPresent) { this.remarksPresent = remarksPresent; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getReviewRequestedAt() { return reviewRequestedAt; }
    public void setReviewRequestedAt(OffsetDateTime reviewRequestedAt) { this.reviewRequestedAt = reviewRequestedAt; }
    public String getReviewTicketId() { return reviewTicketId; }
    public void setReviewTicketId(String reviewTicketId) { this.reviewTicketId = reviewTicketId; }
}
