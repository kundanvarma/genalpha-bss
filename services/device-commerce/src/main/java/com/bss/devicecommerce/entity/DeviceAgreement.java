package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The device lifecycle's anchor: who financed which device on what terms.
 * Financing is a MODEL, not an assumption — the operator may carry the
 * receivable (OPERATOR_BOOK), a partner bank may own it (THIRD_PARTY_LOAN),
 * or a BNPL provider may have paid out at checkout (BNPL). Total cost of
 * ownership is a required, displayed field: "half price" framing that hides
 * a total above the cash price is the ombudsman lesson, not a style choice.
 */
@Entity
@Table(name = "device_agreement")
public class DeviceAgreement {

    public static final String OPERATOR_BOOK = "OPERATOR_BOOK";
    public static final String THIRD_PARTY_LOAN = "THIRD_PARTY_LOAN";
    public static final String BNPL = "BNPL";

    public static final String ACTIVE = "active";
    public static final String SETTLED = "settled";
    public static final String SWAPPED = "swapped";
    public static final String DEFAULTED = "defaulted";
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    /** The subscription/product (or service) the financing rides on. */
    @Column(name = "subscription_ref", length = 64)
    private String subscriptionRef;

    /** The product order that shipped the device — binds the delivery clock. */
    @Column(name = "order_ref", length = 64)
    private String orderRef;

    /** Catalog device model (TMF639-style resource ref). */
    @Column(name = "device_ref", length = 128)
    private String deviceRef;

    @Column(name = "imei", length = 32)
    private String imei;

    @Column(name = "serial_no", length = 64)
    private String serialNo;

    @Column(name = "principal", nullable = false, precision = 12, scale = 2)
    private BigDecimal principal;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "monthly_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "financing_model", nullable = false, length = 24)
    private String financingModel;

    @Column(name = "financier_ref", length = 128)
    private String financierRef;

    @Column(name = "external_agreement_no", length = 64)
    private String externalAgreementNo;

    /** Who owns the device until settled: operator | financier | customer. */
    @Column(name = "title_holder", length = 24)
    private String titleHolder;

    /** paidSharePct | month — which rule unlocks the upgrade rider. */
    @Column(name = "upgrade_rule_type", length = 16)
    private String upgradeRuleType;

    @Column(name = "upgrade_rule_value", precision = 12, scale = 2)
    private BigDecimal upgradeRuleValue;

    /** Guaranteed buy-back / residual value promised by the program. */
    @Column(name = "residual_value", precision = 12, scale = 2)
    private BigDecimal residualValue;

    /** The honest number on every offer face — required, never fine print. */
    @Column(name = "total_cost_of_ownership", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCostOfOwnership;

    /** Device-revenue-vs-cash gap at delivery (operator-book IFRS 15 input). */
    @Column(name = "subsidy_amount", precision = 12, scale = 2)
    private BigDecimal subsidyAmount;

    /** Standard shipping charged at checkout — a withdrawal refunds it too. */
    @Column(name = "shipping_cost", precision = 12, scale = 2)
    private BigDecimal shippingCost;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    /** BNPL: the checkout payment that paid the operator out. */
    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    /** Operator-book local schedule: parts paid so far (of termMonths). */
    @Column(name = "installments_paid", nullable = false)
    private int installmentsPaid;

    /** Third-party loan: the financier's upfront payout landed. */
    @Column(name = "payout_received_at")
    private OffsetDateTime payoutReceivedAt;

    /** Withdrawal clock start = parcel delivery (fallback: activation). */
    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    /** Outstanding grading delta (negative = customer owes) — billing's seam. */
    @Column(name = "trade_in_delta", precision = 12, scale = 2)
    private BigDecimal tradeInDelta;

    @Column(name = "status", nullable = false, length = 16)
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
    public String getSubscriptionRef() { return subscriptionRef; }
    public void setSubscriptionRef(String v) { this.subscriptionRef = v; }
    public String getOrderRef() { return orderRef; }
    public void setOrderRef(String v) { this.orderRef = v; }
    public String getDeviceRef() { return deviceRef; }
    public void setDeviceRef(String v) { this.deviceRef = v; }
    public String getImei() { return imei; }
    public void setImei(String v) { this.imei = v; }
    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String v) { this.serialNo = v; }
    public BigDecimal getPrincipal() { return principal; }
    public void setPrincipal(BigDecimal v) { this.principal = v; }
    public int getTermMonths() { return termMonths; }
    public void setTermMonths(int v) { this.termMonths = v; }
    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public void setMonthlyAmount(BigDecimal v) { this.monthlyAmount = v; }
    public String getFinancingModel() { return financingModel; }
    public void setFinancingModel(String v) { this.financingModel = v; }
    public String getFinancierRef() { return financierRef; }
    public void setFinancierRef(String v) { this.financierRef = v; }
    public String getExternalAgreementNo() { return externalAgreementNo; }
    public void setExternalAgreementNo(String v) { this.externalAgreementNo = v; }
    public String getTitleHolder() { return titleHolder; }
    public void setTitleHolder(String v) { this.titleHolder = v; }
    public String getUpgradeRuleType() { return upgradeRuleType; }
    public void setUpgradeRuleType(String v) { this.upgradeRuleType = v; }
    public BigDecimal getUpgradeRuleValue() { return upgradeRuleValue; }
    public void setUpgradeRuleValue(BigDecimal v) { this.upgradeRuleValue = v; }
    public BigDecimal getResidualValue() { return residualValue; }
    public void setResidualValue(BigDecimal v) { this.residualValue = v; }
    public BigDecimal getTotalCostOfOwnership() { return totalCostOfOwnership; }
    public void setTotalCostOfOwnership(BigDecimal v) { this.totalCostOfOwnership = v; }
    public BigDecimal getSubsidyAmount() { return subsidyAmount; }
    public void setSubsidyAmount(BigDecimal v) { this.subsidyAmount = v; }
    public BigDecimal getShippingCost() { return shippingCost; }
    public void setShippingCost(BigDecimal v) { this.shippingCost = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getPaymentRef() { return paymentRef; }
    public void setPaymentRef(String v) { this.paymentRef = v; }
    public int getInstallmentsPaid() { return installmentsPaid; }
    public void setInstallmentsPaid(int v) { this.installmentsPaid = v; }
    public OffsetDateTime getPayoutReceivedAt() { return payoutReceivedAt; }
    public void setPayoutReceivedAt(OffsetDateTime v) { this.payoutReceivedAt = v; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime v) { this.deliveredAt = v; }
    public BigDecimal getTradeInDelta() { return tradeInDelta; }
    public void setTradeInDelta(BigDecimal v) { this.tradeInDelta = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
