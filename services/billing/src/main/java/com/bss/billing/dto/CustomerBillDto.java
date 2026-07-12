package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    private java.util.Map<String, Object> billingAccount;

    @JsonProperty("billDocument")
    private java.util.List<Object> billDocument = new java.util.ArrayList<>();

    @JsonProperty("amountDue")
    private MoneyDto amountDue;

    @JsonProperty("billingPeriod")
    private Map<String, Object> billingPeriod;

    @JsonProperty("relatedParty")
    private List<Map<String, Object>> relatedParty;

    @JsonProperty("payment")
    private List<Map<String, Object>> payment;

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

    public Map<String, Object> getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(Map<String, Object> billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    public List<Map<String, Object>> getRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(List<Map<String, Object>> relatedParty) {
        this.relatedParty = relatedParty;
    }

    public List<Map<String, Object>> getPayment() {
        return payment;
    }

    public void setPayment(List<Map<String, Object>> payment) {
        this.payment = payment;
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

    public java.util.List<Object> getBillDocument() { return billDocument; }
    public void setBillDocument(java.util.List<Object> billDocument) { this.billDocument = billDocument; }

    public java.util.Map<String, Object> getBillingAccount() { return billingAccount; }
    public void setBillingAccount(java.util.Map<String, Object> billingAccount) { this.billingAccount = billingAccount; }
}
