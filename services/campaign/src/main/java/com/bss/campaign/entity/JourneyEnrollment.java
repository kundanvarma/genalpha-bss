package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One customer walking one journey — where they are, which variant,
 * whether they converted out. */
@Entity
@Table(name = "journey_enrollment")
public class JourneyEnrollment {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "journey_id")
    private String journeyId;

    @Column(name = "party_id")
    private String partyId;

    private String variant = "treated";

    /** active | converted | completed */
    private String status = "active";

    @Column(name = "step_index")
    private int stepIndex;

    @Column(name = "next_action_at")
    private OffsetDateTime nextActionAt;

    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getJourneyId() { return journeyId; }
    public void setJourneyId(String journeyId) { this.journeyId = journeyId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
    public OffsetDateTime getNextActionAt() { return nextActionAt; }
    public void setNextActionAt(OffsetDateTime v) { this.nextActionAt = v; }
    public OffsetDateTime getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(OffsetDateTime v) { this.enrolledAt = v; }
    public OffsetDateTime getConvertedAt() { return convertedAt; }
    public void setConvertedAt(OffsetDateTime v) { this.convertedAt = v; }
}
