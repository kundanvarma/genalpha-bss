package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductOfferingPriceDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("priceType")
    private String priceType;

    @JsonProperty("isBundle")
    private Boolean isBundle;

    @JsonProperty("price")
    private Map<String, Object> price;

    @JsonProperty("prodSpecCharValueUse")
    private java.util.List<Map<String, Object>> prodSpecCharValueUse;

    @JsonProperty("recurringChargePeriodType")
    private String recurringChargePeriodType;

    @JsonProperty("recurringChargePeriodLength")
    private Integer recurringChargePeriodLength;

    @JsonProperty("lifecycleStatus")
    private String lifecycleStatus;

    @JsonProperty("version")
    private String version;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("@type")
    private String type = "ProductOfferingPrice";

    public ProductOfferingPriceDto() {
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

    public String getPriceType() {
        return priceType;
    }

    public void setPriceType(String priceType) {
        this.priceType = priceType;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public Map<String, Object> getPrice() {
        return price;
    }

    public void setPrice(Map<String, Object> price) {
        this.price = price;
    }

    public java.util.List<Map<String, Object>> getProdSpecCharValueUse() {
        return prodSpecCharValueUse;
    }

    public void setProdSpecCharValueUse(java.util.List<Map<String, Object>> prodSpecCharValueUse) {
        this.prodSpecCharValueUse = prodSpecCharValueUse;
    }

    public String getRecurringChargePeriodType() {
        return recurringChargePeriodType;
    }

    public void setRecurringChargePeriodType(String recurringChargePeriodType) {
        this.recurringChargePeriodType = recurringChargePeriodType;
    }

    public Integer getRecurringChargePeriodLength() {
        return recurringChargePeriodLength;
    }

    public void setRecurringChargePeriodLength(Integer recurringChargePeriodLength) {
        this.recurringChargePeriodLength = recurringChargePeriodLength;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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
}
