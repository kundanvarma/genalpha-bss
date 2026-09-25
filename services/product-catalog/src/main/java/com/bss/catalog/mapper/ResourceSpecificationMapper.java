package com.bss.catalog.mapper;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.dto.ResourceSpecificationDto;
import com.bss.catalog.entity.ResourceSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ResourceSpecificationMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };

    static final String BASE_TYPE = "ResourceSpecification";
    static final String LOGICAL_TYPE = "LogicalResourceSpecification";
    static final String PHYSICAL_TYPE = "PhysicalResourceSpecification";

    private final ObjectMapper objectMapper;

    public ResourceSpecificationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResourceSpecificationDto toDto(ResourceSpecification entity) {
        ResourceSpecificationDto dto = new ResourceSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setVersion(entity.getVersion());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setCategory(entity.getCategory());
        // isBundle is mandatory on the wire: absent reads as "not a bundle"
        dto.setIsBundle(entity.getIsBundle() != null && entity.getIsBundle());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setResourceSpecCharacteristic(readJsonObjectList(entity.getResourceSpecCharacteristicJson()));
        dto.setResourceSpecRelationship(readJsonObjectList(entity.getResourceSpecRelationshipJson()));
        dto.setType(typeFor(entity.getCategory()));
        dto.setBaseType(BASE_TYPE);
        dto.setSchemaLocation(ApiConstants.RESOURCE_SCHEMA_BASE + dto.getType() + ".schema.json");
        return dto;
    }

    /** The TMF634 subtype the category implies — "logical" / "physical" spelled the standard's way; else the base. */
    static String typeFor(String category) {
        if ("logical".equalsIgnoreCase(category)) {
            return LOGICAL_TYPE;
        }
        if ("physical".equalsIgnoreCase(category)) {
            return PHYSICAL_TYPE;
        }
        return BASE_TYPE;
    }

    /** The category a client's {@code @type} implies; null when it says nothing about it. */
    static String categoryFor(String type) {
        if (LOGICAL_TYPE.equalsIgnoreCase(type)) {
            return "logical";
        }
        if (PHYSICAL_TYPE.equalsIgnoreCase(type)) {
            return "physical";
        }
        return null;
    }

    public ResourceSpecification toEntity(ResourceSpecificationDto dto) {
        ResourceSpecification entity = new ResourceSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setVersion(dto.getVersion());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        // category wins when given; otherwise a subtype in @type classifies the spec
        entity.setCategory(dto.getCategory() != null ? dto.getCategory() : categoryFor(dto.getType()));
        entity.setIsBundle(dto.getIsBundle() != null && dto.getIsBundle());
        entity.setLastUpdate(dto.getLastUpdate());
        entity.setResourceSpecCharacteristicJson(writeJsonObjectList(dto.getResourceSpecCharacteristic()));
        entity.setResourceSpecRelationshipJson(writeJsonObjectList(dto.getResourceSpecRelationship()));
        return entity;
    }

    public void applyPatch(ResourceSpecificationDto patch, ResourceSpecification entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getDescription() != null) {
            entity.setDescription(patch.getDescription());
        }
        if (patch.getVersion() != null) {
            entity.setVersion(patch.getVersion());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getCategory() != null) {
            entity.setCategory(patch.getCategory());
        } else if (categoryFor(patch.getType()) != null) {
            entity.setCategory(categoryFor(patch.getType()));
        }
        if (patch.getIsBundle() != null) {
            entity.setIsBundle(patch.getIsBundle());
        }
        if (patch.getResourceSpecCharacteristic() != null) {
            entity.setResourceSpecCharacteristicJson(writeJsonObjectList(patch.getResourceSpecCharacteristic()));
        }
        if (patch.getResourceSpecRelationship() != null) {
            entity.setResourceSpecRelationshipJson(writeJsonObjectList(patch.getResourceSpecRelationship()));
        }
    }

    private String writeJsonObjectList(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonObjectList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
