package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** TMF633 ServiceCandidate on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceCandidateDto {

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

    @JsonProperty("validFor")
    private TimePeriod validFor;

    @JsonProperty("category")
    private List<Map<String, Object>> category;

    @JsonProperty("serviceSpecification")
    private EntityRef serviceSpecification;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("@type")
    private String type = "ServiceCandidate";

    @JsonProperty("@baseType")
    private String baseType;

    @JsonProperty("@schemaLocation")
    private String schemaLocation;

    public ServiceCandidateDto() {
    }

    /** TMF TimePeriod: startDateTime / endDateTime. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimePeriod {

        @JsonProperty("startDateTime")
        private OffsetDateTime startDateTime;

        @JsonProperty("endDateTime")
        private OffsetDateTime endDateTime;

        public TimePeriod() {
        }

        public TimePeriod(OffsetDateTime startDateTime, OffsetDateTime endDateTime) {
            this.startDateTime = startDateTime;
            this.endDateTime = endDateTime;
        }

        public OffsetDateTime getStartDateTime() {
            return startDateTime;
        }

        public void setStartDateTime(OffsetDateTime startDateTime) {
            this.startDateTime = startDateTime;
        }

        public OffsetDateTime getEndDateTime() {
            return endDateTime;
        }

        public void setEndDateTime(OffsetDateTime endDateTime) {
            this.endDateTime = endDateTime;
        }
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

    public TimePeriod getValidFor() {
        return validFor;
    }

    public void setValidFor(TimePeriod validFor) {
        this.validFor = validFor;
    }

    public List<Map<String, Object>> getCategory() {
        return category;
    }

    public void setCategory(List<Map<String, Object>> category) {
        this.category = category;
    }

    public EntityRef getServiceSpecification() {
        return serviceSpecification;
    }

    public void setServiceSpecification(EntityRef serviceSpecification) {
        this.serviceSpecification = serviceSpecification;
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
