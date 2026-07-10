package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.entity.ProductSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductSpecificationMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductSpecificationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductSpecificationDto toDto(ProductSpecification entity) {
        ProductSpecificationDto dto = new ProductSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setBrand(entity.getBrand());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setProductSpecCharacteristic(readJsonObjectList(entity.getProductSpecCharacteristicJson()));
        dto.setType("ProductSpecification");
        return dto;
    }

    public ProductSpecification toEntity(ProductSpecificationDto dto) {
        ProductSpecification entity = new ProductSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setBrand(dto.getBrand());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setLastUpdate(dto.getLastUpdate());
        entity.setProductSpecCharacteristicJson(writeJsonObjectList(dto.getProductSpecCharacteristic()));
        return entity;
    }

    public void applyPatch(ProductSpecificationDto patch, ProductSpecification entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getBrand() != null) {
            entity.setBrand(patch.getBrand());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getProductSpecCharacteristic() != null) {
            entity.setProductSpecCharacteristicJson(writeJsonObjectList(patch.getProductSpecCharacteristic()));
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
