package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A configuration rule the quote builder enforces: an offering requires or
 *  excludes another, or must be within a quantity bound. The "C" in CPQ. */
@Entity
@Table(name = "quote_config_rule")
public class QuoteConfigRule {

    public static final String REQUIRES = "requires";
    public static final String EXCLUDES = "excludes";
    public static final String MIN_QTY = "minQty";
    public static final String MAX_QTY = "maxQty";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "rule_type", nullable = false, length = 16)
    private String ruleType;

    @Column(name = "subject_offering", nullable = false, length = 255)
    private String subjectOffering;

    @Column(name = "object_offering", length = 255)
    private String objectOffering;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String v) { this.ruleType = v; }
    public String getSubjectOffering() { return subjectOffering; }
    public void setSubjectOffering(String v) { this.subjectOffering = v; }
    public String getObjectOffering() { return objectOffering; }
    public void setObjectOffering(String v) { this.objectOffering = v; }
    public Integer getQty() { return qty; }
    public void setQty(Integer v) { this.qty = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
