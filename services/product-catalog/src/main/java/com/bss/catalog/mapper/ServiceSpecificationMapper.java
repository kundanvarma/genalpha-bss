package com.bss.catalog.mapper;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.dto.ServiceSpecificationDto;
import com.bss.catalog.entity.ServiceSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ServiceSpecificationMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };

    static final String BASE_TYPE = "ServiceSpecification";
    static final String CFS_TYPE = "CustomerFacingServiceSpecification";
    static final String RFS_TYPE = "ResourceFacingServiceSpecification";

    private final ObjectMapper objectMapper;

    public ServiceSpecificationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServiceSpecificationDto toDto(ServiceSpecification entity) {
        ServiceSpecificationDto dto = new ServiceSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setVersion(entity.getVersion());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        // isBundle is mandatory on the wire: absent reads as "not a bundle"
        dto.setIsBundle(entity.getIsBundle() != null && entity.getIsBundle());
        dto.setServiceType(entity.getServiceType());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setServiceSpecCharacteristic(readJsonObjectList(entity.getServiceSpecCharacteristicJson()));
        dto.setServiceSpecRelationship(readJsonObjectList(entity.getServiceSpecRelationshipJson()));
        dto.setType(typeFor(entity.getServiceType()));
        dto.setBaseType(BASE_TYPE);
        dto.setSchemaLocation(ApiConstants.SERVICE_SCHEMA_BASE + dto.getType() + ".schema.json");
        return dto;
    }

    /** The TMF633 subtype the CFS/RFS tag stands for — the same fact, spelled the standard's way. */
    static String typeFor(String serviceType) {
        if ("CFS".equalsIgnoreCase(serviceType)) {
            return CFS_TYPE;
        }
        if ("RFS".equalsIgnoreCase(serviceType)) {
            return RFS_TYPE;
        }
        return BASE_TYPE;
    }

    /** The CFS/RFS tag a client's {@code @type} implies; null when it says nothing about it. */
    static String serviceTypeFor(String type) {
        if (CFS_TYPE.equalsIgnoreCase(type)) {
            return "CFS";
        }
        if (RFS_TYPE.equalsIgnoreCase(type)) {
            return "RFS";
        }
        return null;
    }

    public ServiceSpecification toEntity(ServiceSpecificationDto dto) {
        ServiceSpecification entity = new ServiceSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setVersion(dto.getVersion());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setIsBundle(dto.getIsBundle() != null && dto.getIsBundle());
        // serviceType wins when given; otherwise a subtype in @type classifies the spec
        entity.setServiceType(dto.getServiceType() != null ? dto.getServiceType() : serviceTypeFor(dto.getType()));
        entity.setLastUpdate(dto.getLastUpdate());
        entity.setServiceSpecCharacteristicJson(writeJsonObjectList(dto.getServiceSpecCharacteristic()));
        entity.setServiceSpecRelationshipJson(writeJsonObjectList(dto.getServiceSpecRelationship()));
        return entity;
    }

    public void applyPatch(ServiceSpecificationDto patch, ServiceSpecification entity) {
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
        if (patch.getIsBundle() != null) {
            entity.setIsBundle(patch.getIsBundle());
        }
        if (patch.getServiceType() != null) {
            entity.setServiceType(patch.getServiceType());
        } else if (serviceTypeFor(patch.getType()) != null) {
            entity.setServiceType(serviceTypeFor(patch.getType()));
        }
        if (patch.getServiceSpecCharacteristic() != null) {
            entity.setServiceSpecCharacteristicJson(writeJsonObjectList(patch.getServiceSpecCharacteristic()));
        }
        if (patch.getServiceSpecRelationship() != null) {
            entity.setServiceSpecRelationshipJson(writeJsonObjectList(patch.getServiceSpecRelationship()));
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
