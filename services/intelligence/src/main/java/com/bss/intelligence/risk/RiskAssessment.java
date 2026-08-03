package com.bss.intelligence.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One answered TMF696 assessment: the score, its level, and the JSON
 * evidence it was computed from — kept, because a refusal built on this
 * row may need to be explained months later. */
@Entity
@Table(name = "risk_assessment")
public class RiskAssessment {

    public static final String PARTY = "party";
    public static final String PRODUCT_ORDER = "productOrder";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "risk_level", nullable = false, length = 8)
    private String riskLevel;

    @Column(name = "result", length = 6000)
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public RiskAssessment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
