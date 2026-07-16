package com.bss.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductDto {

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonProperty("previousOffering")
    private java.util.Map<String, Object> previousOffering;

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonProperty("offeringChangedAt")
    private String offeringChangedAt;

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonProperty("startDate")
    private String startDate;

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("status")
    private String status;

    @JsonProperty("productOffering")
    private Map<String, Object> productOffering;

    @JsonProperty("billingAccount")
    private Map<String, Object> billingAccount;

    @JsonProperty("productCharacteristic")
    private List<Map<String, Object>> productCharacteristic;

    @JsonProperty("productPrice")
    private List<Map<String, Object>> productPrice;

    @JsonProperty("relatedParty")
    private List<Map<String, Object>> relatedParty;

    @JsonProperty("@type")
    private String type = "Product";

    public ProductDto() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getProductOffering() {
        return productOffering;
    }

    public void setProductOffering(Map<String, Object> productOffering) {
        this.productOffering = productOffering;
    }

    public Map<String, Object> getBillingAccount() {
        return billingAccount;
    }

    public void setBillingAccount(Map<String, Object> billingAccount) {
        this.billingAccount = billingAccount;
    }

    public List<Map<String, Object>> getProductCharacteristic() {
        return productCharacteristic;
    }

    public void setProductCharacteristic(List<Map<String, Object>> productCharacteristic) {
        this.productCharacteristic = productCharacteristic;
    }

    public List<Map<String, Object>> getProductPrice() {
        return productPrice;
    }

    public void setProductPrice(List<Map<String, Object>> productPrice) {
        this.productPrice = productPrice;
    }

    public List<Map<String, Object>> getRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(List<Map<String, Object>> relatedParty) {
        this.relatedParty = relatedParty;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public java.util.Map<String, Object> getPreviousOffering() { return previousOffering; }
    public void setPreviousOffering(java.util.Map<String, Object> v) { this.previousOffering = v; }
    public String getOfferingChangedAt() { return offeringChangedAt; }
    public void setOfferingChangedAt(String v) { this.offeringChangedAt = v; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String v) { this.startDate = v; }
}
