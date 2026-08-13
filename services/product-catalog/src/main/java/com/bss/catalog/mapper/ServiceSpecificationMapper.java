package com.bss.catalog.mapper;

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
        dto.setIsBundle(entity.getIsBundle());
        dto.setServiceType(entity.getServiceType());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setServiceSpecCharacteristic(readJsonObjectList(entity.getServiceSpecCharacteristicJson()));
        dto.setServiceSpecRelationship(readJsonObjectList(entity.getServiceSpecRelationshipJson()));
        dto.setType("ServiceSpecification");
        return dto;
    }

    public ServiceSpecification toEntity(ServiceSpecificationDto dto) {
        ServiceSpecification entity = new ServiceSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setVersion(dto.getVersion());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setIsBundle(dto.getIsBundle());
        entity.setServiceType(dto.getServiceType());
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
