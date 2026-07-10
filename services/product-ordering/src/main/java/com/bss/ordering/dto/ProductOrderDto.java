package com.bss.ordering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductOrderDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @JsonProperty("state")
    private String state;

    @NotEmpty(message = "productOrderItem must contain at least one item")
    @JsonProperty("productOrderItem")
    private List<Map<String, Object>> productOrderItem;

    @JsonProperty("relatedParty")
    private List<Map<String, Object>> relatedParty;

    @JsonProperty("payment")
    private List<Map<String, Object>> payment;

    @JsonProperty("description")
    private String description;

    @JsonProperty("category")
    private String category;

    @JsonProperty("productOfferingId")
    private String productOfferingId;

    @JsonProperty("billingAccountId")
    private String billingAccountId;

    @JsonProperty("orderDate")
    private OffsetDateTime orderDate;

    @JsonProperty("@type")
    private String type = "ProductOrder";

    public ProductOrderDto() {
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getProductOfferingId() {
        return productOfferingId;
    }

    public void setProductOfferingId(String productOfferingId) {
        this.productOfferingId = productOfferingId;
    }

    public List<Map<String, Object>> getProductOrderItem() {
        return productOrderItem;
    }

    public void setProductOrderItem(List<Map<String, Object>> productOrderItem) {
        this.productOrderItem = productOrderItem;
    }

    public List<Map<String, Object>> getRelatedParty() {
        return relatedParty;
    }

    public void setRelatedParty(List<Map<String, Object>> relatedParty) {
        this.relatedParty = relatedParty;
    }

    public String getBillingAccountId() {
        return billingAccountId;
    }

    public void setBillingAccountId(String billingAccountId) {
        this.billingAccountId = billingAccountId;
    }

    public OffsetDateTime getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(OffsetDateTime orderDate) {
        this.orderDate = orderDate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Map<String, Object>> getPayment() {
        return payment;
    }

    public void setPayment(List<Map<String, Object>> payment) {
        this.payment = payment;
    }
}
