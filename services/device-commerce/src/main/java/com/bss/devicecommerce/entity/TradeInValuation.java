package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A trade-in from first estimate to money: IMEI + guided condition answers
 * give an instant estimate off the residual table; the device mails in; a
 * grading partner sets the final value; the delta refunds or charges. A
 * blacklisted IMEI is worth exactly zero — quoted honestly, up front.
 */
@Entity
@Table(name = "trade_in_valuation")
public class TradeInValuation {

    public static final String QUOTED = "quoted";
    public static final String ACCEPTED = "accepted";
    public static final String IN_TRANSIT = "in-transit";
    public static final String GRADED = "graded";
    public static final String REVALUED = "revalued";
    public static final String SETTLED = "settled";
    public static final String REJECTED_RETURNED = "rejected-returned";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "imei", nullable = false, length = 32)
    private String imei;

    /** Catalog device model the estimate keys on. */
    @Column(name = "device_ref", nullable = false, length = 128)
    private String deviceRef;

    /** The guided self-assessment answers, verbatim (JSON). */
    @Column(name = "condition_json", length = 2000)
    private String conditionJson;

    @Column(name = "estimated_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedValue;

    /** Partner's final word after physical grading. */
    @Column(name = "final_value", precision = 12, scale = 2)
    private BigDecimal finalValue;

    /** final − estimate: positive refunds, negative raises a charge. */
    @Column(name = "delta", precision = 12, scale = 2)
    private BigDecimal delta;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "offer_expiry", nullable = false)
    private OffsetDateTime offerExpiry;

    @Column(name = "channel", length = 32)
    private String channel;

    /** The purchase payment a positive delta refunds against (PSP path). */
    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    @Column(name = "refund_ref", length = 64)
    private String refundRef;

    /** Agreement this trade-in feeds (upgrade/swap), when there is one. */
    @Column(name = "agreement_ref", length = 36)
    private String agreementRef;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getHref() { return href; }
    public void setHref(String v) { this.href = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getImei() { return imei; }
    public void setImei(String v) { this.imei = v; }
    public String getDeviceRef() { return deviceRef; }
    public void setDeviceRef(String v) { this.deviceRef = v; }
    public String getConditionJson() { return conditionJson; }
    public void setConditionJson(String v) { this.conditionJson = v; }
    public BigDecimal getEstimatedValue() { return estimatedValue; }
    public void setEstimatedValue(BigDecimal v) { this.estimatedValue = v; }
    public BigDecimal getFinalValue() { return finalValue; }
    public void setFinalValue(BigDecimal v) { this.finalValue = v; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal v) { this.delta = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public OffsetDateTime getOfferExpiry() { return offerExpiry; }
    public void setOfferExpiry(OffsetDateTime v) { this.offerExpiry = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getPaymentRef() { return paymentRef; }
    public void setPaymentRef(String v) { this.paymentRef = v; }
    public String getRefundRef() { return refundRef; }
    public void setRefundRef(String v) { this.refundRef = v; }
    public String getAgreementRef() { return agreementRef; }
    public void setAgreementRef(String v) { this.agreementRef = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
