package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * TMF634 ResourceSpecification on the wire. The standard's own open blocks
 * ({@code resourceSpecCharacteristic}, {@code resourceSpecRelationship}) stay
 * open, like the TMF633 twin; everything else is a named field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceSpecificationDto {

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

    @JsonProperty("category")
    private String category;

    @JsonProperty("isBundle")
    private Boolean isBundle;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("resourceSpecCharacteristic")
    private List<Map<String, Object>> resourceSpecCharacteristic;

    @JsonProperty("resourceSpecRelationship")
    private List<Map<String, Object>> resourceSpecRelationship;

    /** TMF634 polymorphism: LogicalResourceSpecification | PhysicalResourceSpecification, else the base. */
    @JsonProperty("@type")
    private String type = "ResourceSpecification";

    @JsonProperty("@baseType")
    private String baseType;

    @JsonProperty("@schemaLocation")
    private String schemaLocation;

    public ResourceSpecificationDto() {
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public List<Map<String, Object>> getResourceSpecCharacteristic() {
        return resourceSpecCharacteristic;
    }

    public void setResourceSpecCharacteristic(List<Map<String, Object>> resourceSpecCharacteristic) {
        this.resourceSpecCharacteristic = resourceSpecCharacteristic;
    }

    public List<Map<String, Object>> getResourceSpecRelationship() {
        return resourceSpecRelationship;
    }

    public void setResourceSpecRelationship(List<Map<String, Object>> resourceSpecRelationship) {
        this.resourceSpecRelationship = resourceSpecRelationship;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBaseType() {
        return baseType;
    }

    public void setBaseType(String baseType) {
        this.baseType = baseType;
    }

    public String getSchemaLocation() {
        return schemaLocation;
    }

    public void setSchemaLocation(String schemaLocation) {
        this.schemaLocation = schemaLocation;
    }
}
