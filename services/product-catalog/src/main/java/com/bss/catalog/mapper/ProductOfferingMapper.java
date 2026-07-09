package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProductOfferingMapper {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductOfferingMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductOfferingDto toDto(ProductOffering entity) {
        ProductOfferingDto dto = new ProductOfferingDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setVersion(entity.getVersion());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setProductSpecification(readJsonObject(entity.getProductSpecificationJson()));
        dto.setType("ProductOffering");
        return dto;
    }

    public ProductOffering toEntity(ProductOfferingDto dto) {
        ProductOffering entity = new ProductOffering();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setVersion(dto.getVersion());
        entity.setLastUpdate(dto.getLastUpdate());
        entity.setProductSpecificationJson(writeJsonObject(dto.getProductSpecification()));
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(ProductOfferingDto patch, ProductOffering entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getDescription() != null) {
            entity.setDescription(patch.getDescription());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getVersion() != null) {
            entity.setVersion(patch.getVersion());
        }
        if (patch.getProductSpecification() != null) {
            entity.setProductSpecificationJson(writeJsonObject(patch.getProductSpecification()));
        }
    }

    private String writeJsonObject(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON object", e);
        }
    }

    private Map<String, Object> readJsonObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON object is unreadable", e);
        }
    }
}
