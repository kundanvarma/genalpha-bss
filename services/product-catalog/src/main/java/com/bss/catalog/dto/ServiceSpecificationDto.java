package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceSpecificationDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("version")
    private String version;

    @JsonProperty("lifecycleStatus")
    private String lifecycleStatus;

    @JsonProperty("isBundle")
    private Boolean isBundle;

    /** CFS | RFS — convenience classifier for the console; the real link is the relationship. */
    @JsonProperty("serviceType")
    private String serviceType;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("serviceSpecCharacteristic")
    private List<Map<String, Object>> serviceSpecCharacteristic;

    @JsonProperty("serviceSpecRelationship")
    private List<Map<String, Object>> serviceSpecRelationship;

    @JsonProperty("@type")
    private String type = "ServiceSpecification";

    public ServiceSpecificationDto() {
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public List<Map<String, Object>> getServiceSpecCharacteristic() {
        return serviceSpecCharacteristic;
    }

    public void setServiceSpecCharacteristic(List<Map<String, Object>> serviceSpecCharacteristic) {
        this.serviceSpecCharacteristic = serviceSpecCharacteristic;
    }

    public List<Map<String, Object>> getServiceSpecRelationship() {
        return serviceSpecRelationship;
    }

    public void setServiceSpecRelationship(List<Map<String, Object>> serviceSpecRelationship) {
        this.serviceSpecRelationship = serviceSpecRelationship;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
