package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A lead-scoring signal → points. field ∈ source | companyPresent |
 *  companySizeMin | keyword. */
@Entity
@Table(name = "lead_scoring_rule")
public class LeadScoringRule {

    public static final String SOURCE = "source";
    public static final String COMPANY_PRESENT = "companyPresent";
    public static final String COMPANY_SIZE_MIN = "companySizeMin";
    public static final String KEYWORD = "keyword";
    /** value ∈ knownProspect | engaged | opened | clicked — read from the CDP. */
    public static final String ENGAGEMENT = "engagement";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "field", nullable = false, length = 24)
    private String field;

    @Column(name = "signal_value", length = 255)
    private String value;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getField() { return field; }
    public void setField(String v) { this.field = v; }
    public String getValue() { return value; }
    public void setValue(String v) { this.value = v; }
    public int getPoints() { return points; }
    public void setPoints(int v) { this.points = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
