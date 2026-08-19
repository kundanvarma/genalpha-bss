package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * What the battery concluded about one signal — with the receipts: every
 * classified field's evidence quote was verified VERBATIM against the
 * signal text before this row was accepted. Provider+model name who spoke.
 */
@Entity
@Table(name = "signal_classification")
public class SignalClassification {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "signal_id", nullable = false)
    private String signalId;

    private String sentiment;

    private String aspect;

    private String category;

    @Column(name = "pain_point", length = 500)
    private String painPoint;

    @Column(name = "pain_impact")
    private Integer painImpact;

    @Column(name = "loyalty_indicator")
    private String loyaltyIndicator;

    @Column(name = "churn_signal", nullable = false)
    private boolean churnSignal;

    @Column(name = "churn_reason")
    private String churnReason;

    @Column(length = 2000)
    private String evidence;

    private String provider;

    private String model;

    @Column(name = "classified_at", nullable = false)
    private OffsetDateTime classifiedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String v) { this.sentiment = v; }
    public String getAspect() { return aspect; }
    public void setAspect(String v) { this.aspect = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getPainPoint() { return painPoint; }
    public void setPainPoint(String v) { this.painPoint = v; }
    public Integer getPainImpact() { return painImpact; }
    public void setPainImpact(Integer v) { this.painImpact = v; }
    public String getLoyaltyIndicator() { return loyaltyIndicator; }
    public void setLoyaltyIndicator(String v) { this.loyaltyIndicator = v; }
    public boolean isChurnSignal() { return churnSignal; }
    public void setChurnSignal(boolean v) { this.churnSignal = v; }
    public String getChurnReason() { return churnReason; }
    public void setChurnReason(String v) { this.churnReason = v; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String v) { this.evidence = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public OffsetDateTime getClassifiedAt() { return classifiedAt; }
    public void setClassifiedAt(OffsetDateTime v) { this.classifiedAt = v; }
}
