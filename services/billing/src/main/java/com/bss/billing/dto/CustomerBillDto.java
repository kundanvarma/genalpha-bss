package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * TMF678 CustomerBill: the wire face of a bill, and the PATCH body that
 * settles one. Mutable because both the mapper and the settle path build it
 * field by field; every nested value is a typed record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "href", "billNo", "state", "billingAccount", "billDocument", "amountDue", "installmentPlan",
        "dispute", "billingPeriod", "relatedParty", "payment", "distributionChannel", "billDate", "lastUpdate", "@type"})
public class CustomerBillDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @JsonProperty("billNo")
    private String billNo;

    @JsonProperty("state")
    private String state;

    @JsonProperty("billingAccount")
    private EntityRef billingAccount;

    @JsonProperty("billDocument")
    private List<AttachmentRef> billDocument = new java.util.ArrayList<>();

    @JsonProperty("amountDue")
    private MoneyDto amountDue;

    @JsonProperty("installmentPlan")
    private InstallmentPlanView installmentPlan;

    @JsonProperty("dispute")
    private DisputeChip dispute;

    @JsonProperty("billingPeriod")
    private TimePeriod billingPeriod;

    @JsonProperty("relatedParty")
    private List<RelatedPartyRef> relatedParty;

    @JsonProperty("payment")
    private List<PaymentRef> payment;

    @JsonProperty("distributionChannel")
    private String distributionChannel;

    @JsonProperty("billDate")
    private OffsetDateTime billDate;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("@type")
    private String type = "CustomerBill";

    public CustomerBillDto() {
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

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public MoneyDto getAmountDue() {
        return amountDue;
    }

    public void setAmountDue(MoneyDto amountDue) {
        this.amountDue = amountDue;
    }

    public TimePeriod getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(TimePeriod billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    public List<RelatedPartyRef> getRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(List<RelatedPartyRef> relatedParty) {
        this.relatedParty = relatedParty;
    }

    public List<PaymentRef> getPayment() {
        return payment;
    }

    public void setPayment(List<PaymentRef> payment) {
        this.payment = payment;
    }

    /** The settling payment's id, or null when the patch names none. */
    public String paymentId() {
        return payment == null || payment.isEmpty() || payment.get(0) == null ? null : payment.get(0).id();
    }

    public String getDistributionChannel() {
        return distributionChannel;
    }

    public void setDistributionChannel(String distributionChannel) {
        this.distributionChannel = distributionChannel;
    }

    public OffsetDateTime getBillDate() {
        return billDate;
    }

    public void setBillDate(OffsetDateTime billDate) {
        this.billDate = billDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<AttachmentRef> getBillDocument() { return billDocument; }
    public void setBillDocument(List<AttachmentRef> billDocument) { this.billDocument = billDocument; }

    public EntityRef getBillingAccount() { return billingAccount; }
    public void setBillingAccount(EntityRef billingAccount) { this.billingAccount = billingAccount; }

    public InstallmentPlanView getInstallmentPlan() { return installmentPlan; }
    public void setInstallmentPlan(InstallmentPlanView v) { this.installmentPlan = v; }
    public DisputeChip getDispute() { return dispute; }
    public void setDispute(DisputeChip v) { this.dispute = v; }
}
