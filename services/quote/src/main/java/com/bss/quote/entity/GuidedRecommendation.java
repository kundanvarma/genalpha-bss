package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A guided-selling rule: an answer to a question recommends an offering. */
@Entity
@Table(name = "guided_recommendation")
public class GuidedRecommendation {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "question_key", nullable = false, length = 64)
    private String questionKey;

    @Column(name = "answer_value", nullable = false, length = 255)
    private String answerValue;

    @Column(name = "offering_name", nullable = false, length = 255)
    private String offeringName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getQuestionKey() { return questionKey; }
    public void setQuestionKey(String v) { this.questionKey = v; }
    public String getAnswerValue() { return answerValue; }
    public void setAnswerValue(String v) { this.answerValue = v; }
    public String getOfferingName() { return offeringName; }
    public void setOfferingName(String v) { this.offeringName = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
