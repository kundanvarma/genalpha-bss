package com.bss.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment")
public class Payment {

    public static final String AUTHORIZED = "authorized";
    public static final String CAPTURED = "captured";
    public static final String VOIDED = "voided";
    public static final String REFUNDED = "refunded";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "amount_value", nullable = false)
    private BigDecimal amountValue;

    @Column(name = "amount_unit", nullable = false, length = 8)
    private String amountUnit;

    @Column(name = "method_type", length = 64)
    private String methodType;

    /** Safe display form of the payment method, e.g. "bankCard •••• 4242". */
    @Column(name = "method_label", length = 64)
    private String methodLabel;

    @Column(name = "settlement_ref", length = 64)
    private String settlementRef;

    /** Running total already given back (partial refunds accumulate). */
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal refundedAmount = java.math.BigDecimal.ZERO;

    @Column(name = "psp_provider", length = 32)
    private String pspProvider;

    @Column(name = "authorization_code", length = 64)
    private String authorizationCode;

    /** The order this payment settles, once known. */
    @Column(name = "correlator_id", length = 36)
    private String correlatorId;

    /** The customer party this payment belongs to; drives channel scoping. */
    @Column(name = "owner_party_id", length = 36)
    private String ownerPartyId;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "payment_date")
    private OffsetDateTime paymentDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public Payment() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getAmountValue() {
        return amountValue;
    }

    public void setAmountValue(BigDecimal amountValue) {
        this.amountValue = amountValue;
    }

    public String getAmountUnit() {
        return amountUnit;
    }

    public void setAmountUnit(String amountUnit) {
        this.amountUnit = amountUnit;
    }

    public String getMethodType() {
        return methodType;
    }

    public void setMethodType(String methodType) {
        this.methodType = methodType;
    }

    public String getMethodLabel() {
        return methodLabel;
    }

    public void setMethodLabel(String methodLabel) {
        this.methodLabel = methodLabel;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getSettlementRef() { return settlementRef; }
    public void setSettlementRef(String settlementRef) { this.settlementRef = settlementRef; }
    public String getPspProvider() { return pspProvider; }
    public void setPspProvider(String pspProvider) { this.pspProvider = pspProvider; }

    public String getCorrelatorId() {
        return correlatorId;
    }

    public void setCorrelatorId(String correlatorId) {
        this.correlatorId = correlatorId;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public OffsetDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(OffsetDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public java.math.BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(java.math.BigDecimal v) { this.refundedAmount = v; }
}
