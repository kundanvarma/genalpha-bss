package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductOfferingDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("lifecycleStatus")
    private String lifecycleStatus;

    @JsonProperty("version")
    private String version;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("productSpecification")
    private Map<String, Object> productSpecification;

    @JsonProperty("isBundle")
    private Boolean isBundle;

    @JsonProperty("bundledProductOffering")
    private List<Map<String, Object>> bundledProductOffering;

    @JsonProperty("productOfferingPrice")
    private List<Map<String, Object>> productOfferingPrice;

    @JsonProperty("@type")
    private String type = "ProductOffering";

    public ProductOfferingDto() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public Map<String, Object> getProductSpecification() {
        return productSpecification;
    }

    public void setProductSpecification(Map<String, Object> productSpecification) {
        this.productSpecification = productSpecification;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public List<Map<String, Object>> getBundledProductOffering() {
        return bundledProductOffering;
    }

    public void setBundledProductOffering(List<Map<String, Object>> bundledProductOffering) {
        this.bundledProductOffering = bundledProductOffering;
    }

    public List<Map<String, Object>> getProductOfferingPrice() {
        return productOfferingPrice;
    }

    public void setProductOfferingPrice(List<Map<String, Object>> productOfferingPrice) {
        this.productOfferingPrice = productOfferingPrice;
    }
}
