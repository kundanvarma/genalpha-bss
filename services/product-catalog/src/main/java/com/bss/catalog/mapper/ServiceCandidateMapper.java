package com.bss.catalog.mapper;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.dto.ServiceCandidateDto;
import com.bss.catalog.entity.ServiceCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ServiceCandidateMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };
    private static final TypeReference<EntityRef> SPEC_REF = new TypeReference<>() {
    };
    static final String TYPE = "ServiceCandidate";

    private final ObjectMapper objectMapper;

    public ServiceCandidateMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServiceCandidateDto toDto(ServiceCandidate entity) {
        ServiceCandidateDto dto = new ServiceCandidateDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setVersion(entity.getVersion());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        if (entity.getValidFrom() != null || entity.getValidTo() != null) {
            dto.setValidFor(new ServiceCandidateDto.TimePeriod(entity.getValidFrom(), entity.getValidTo()));
        }
        dto.setCategory(readJson(entity.getCategoryJson(), JSON_OBJECT_LIST));
        dto.setServiceSpecification(readJson(entity.getServiceSpecificationJson(), SPEC_REF));
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType(TYPE);
        dto.setSchemaLocation(ApiConstants.SERVICE_SCHEMA_BASE + TYPE + ".schema.json");
        return dto;
    }

    public ServiceCandidate toEntity(ServiceCandidateDto dto) {
        ServiceCandidate entity = new ServiceCandidate();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setVersion(dto.getVersion());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        if (dto.getValidFor() != null) {
            entity.setValidFrom(dto.getValidFor().getStartDateTime());
            entity.setValidTo(dto.getValidFor().getEndDateTime());
        }
        entity.setCategoryJson(writeJson(dto.getCategory()));
        entity.setServiceSpecificationJson(writeJson(dto.getServiceSpecification()));
        entity.setLastUpdate(dto.getLastUpdate());
        return entity;
    }

    public void applyPatch(ServiceCandidateDto patch, ServiceCandidate entity) {
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
        if (patch.getValidFor() != null) {
            entity.setValidFrom(patch.getValidFor().getStartDateTime());
            entity.setValidTo(patch.getValidFor().getEndDateTime());
        }
        if (patch.getCategory() != null) {
            entity.setCategoryJson(writeJson(patch.getCategory()));
        }
        if (patch.getServiceSpecification() != null) {
            entity.setServiceSpecificationJson(writeJson(patch.getServiceSpecification()));
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }
}
