package com.bss.revenue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A proposed change to live financial configuration, with its own life:
 * drafted, validated, approved, activated. These settings decide which account
 * real money lands in, so the ladder — not a keystroke — is what changes them.
 *
 * <p>The row keeps both sides. {@code current*} is what the account said when
 * the change was drafted; activation compares it against the live row and
 * refuses when somebody else moved it meanwhile.
 */
@Entity
@Table(name = "financial_config_change")
public class FinancialConfigChange {

    /** The rungs. A change climbs one at a time and never skips. */
    public static final String DRAFT = "draft";
    public static final String VALIDATED = "validated";
    public static final String APPROVED = "approved";
    public static final String ACTIVE = "active";
    public static final String WITHDRAWN = "withdrawn";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "posting_key")
    private String postingKey;

    @Column(name = "proposed_code")
    private String proposedCode;

    @Column(name = "proposed_name")
    private String proposedName;

    @Column(name = "proposed_value")
    private BigDecimal proposedValue;

    @Column(name = "current_code")
    private String currentCode;

    @Column(name = "current_name")
    private String currentName;

    @Column(name = "current_value")
    private BigDecimal currentValue;

    private String reason;

    private String state;

    /** The last validation's verdict, one finding per line: severity + tab + message. */
    private String findings;

    @Column(name = "postings_using")
    private long postingsUsing;

    @Column(name = "drafted_by")
    private String draftedBy;

    @Column(name = "drafted_at")
    private OffsetDateTime draftedAt;

    @Column(name = "validated_by")
    private String validatedBy;

    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "activated_by")
    private String activatedBy;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

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

    public String getPostingKey() {
        return postingKey;
    }

    public void setPostingKey(String postingKey) {
        this.postingKey = postingKey;
    }

    public String getProposedCode() {
        return proposedCode;
    }

    public void setProposedCode(String proposedCode) {
        this.proposedCode = proposedCode;
    }

    public String getProposedName() {
        return proposedName;
    }

    public void setProposedName(String proposedName) {
        this.proposedName = proposedName;
    }

    public BigDecimal getProposedValue() {
        return proposedValue;
    }

    public void setProposedValue(BigDecimal proposedValue) {
        this.proposedValue = proposedValue;
    }

    public String getCurrentCode() {
        return currentCode;
    }

    public void setCurrentCode(String currentCode) {
        this.currentCode = currentCode;
    }

    public String getCurrentName() {
        return currentName;
    }

    public void setCurrentName(String currentName) {
        this.currentName = currentName;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getFindings() {
        return findings;
    }

    public void setFindings(String findings) {
        this.findings = findings;
    }

    public long getPostingsUsing() {
        return postingsUsing;
    }

    public void setPostingsUsing(long postingsUsing) {
        this.postingsUsing = postingsUsing;
    }

    public String getDraftedBy() {
        return draftedBy;
    }

    public void setDraftedBy(String draftedBy) {
        this.draftedBy = draftedBy;
    }

    public OffsetDateTime getDraftedAt() {
        return draftedAt;
    }

    public void setDraftedAt(OffsetDateTime draftedAt) {
        this.draftedAt = draftedAt;
    }

    public String getValidatedBy() {
        return validatedBy;
    }

    public void setValidatedBy(String validatedBy) {
        this.validatedBy = validatedBy;
    }

    public OffsetDateTime getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(OffsetDateTime validatedAt) {
        this.validatedAt = validatedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(OffsetDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    public OffsetDateTime getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(OffsetDateTime activatedAt) {
        this.activatedAt = activatedAt;
    }
}
