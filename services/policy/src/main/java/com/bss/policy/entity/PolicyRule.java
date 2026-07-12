package com.bss.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A business rule authored as data. {@code condition} is a JSON-logic
 * expression evaluated against a request context at a decision point
 * ({@code domain}); when it matches, {@code effect} ("deny") applies and
 * {@code message} is surfaced. Editing/adding a rule is a row change, not a
 * redeploy — that is the point of this component.
 */
@Entity
@Table(name = "policy_rule")
public class PolicyRule {

    @Id
    private String id;
    private String href;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;
    private String description;
    private String domain;
    private String effect;
    private int priority;
    private boolean enabled;
    private String condition;
    private String message;

    // Pricing rules only (domain='pricing', effect='adjust'): 'percent' | 'amount'.
    @Column(name = "adjustment_type")
    private String adjustmentType;
    // Signed: negative = discount, positive = surcharge.
    @Column(name = "adjustment_value")
    private java.math.BigDecimal adjustmentValue;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAdjustmentType() {
        return adjustmentType;
    }

    public void setAdjustmentType(String adjustmentType) {
        this.adjustmentType = adjustmentType;
    }

    public java.math.BigDecimal getAdjustmentValue() {
        return adjustmentValue;
    }

    public void setAdjustmentValue(java.math.BigDecimal adjustmentValue) {
        this.adjustmentValue = adjustmentValue;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
