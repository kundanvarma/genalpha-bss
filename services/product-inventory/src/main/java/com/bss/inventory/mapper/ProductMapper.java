package com.bss.inventory.mapper;

import com.bss.inventory.dto.ProductDto;
import com.bss.inventory.entity.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductMapper {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductDto toDto(Product entity) {
        ProductDto dto = new ProductDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setProductOffering(readObject(entity.getProductOfferingJson()));
        dto.setBillingAccount(readObject(entity.getBillingAccountJson()));
        // TMF637 marks the collections mandatory in responses: default to empty.
        dto.setProductCharacteristic(orEmpty(readArray(entity.getProductCharacteristicJson())));
        dto.setProductPrice(orEmpty(readArray(entity.getProductPriceJson())));
        dto.setRelatedParty(orEmpty(readArray(entity.getRelatedPartyJson())));
        if (entity.getPreviousOfferingJson() != null) {
            dto.setPreviousOffering(readObject(entity.getPreviousOfferingJson()));
        }
        if (entity.getOfferingChangedAt() != null) {
            dto.setOfferingChangedAt(entity.getOfferingChangedAt().toString());
        }
        if (entity.getStartDate() != null) {
            dto.setStartDate(entity.getStartDate().toString());
        }
        if (entity.getTerminationDate() != null) {
            dto.setTerminationDate(entity.getTerminationDate().toString());
        }
        dto.setType("Product");
        return dto;
    }

    public Product toEntity(ProductDto dto) {
        Product entity = new Product();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setProductOfferingJson(writeJson(dto.getProductOffering()));
        entity.setBillingAccountJson(writeJson(dto.getBillingAccount()));
        entity.setProductCharacteristicJson(writeJson(dto.getProductCharacteristic()));
        entity.setProductPriceJson(writeJson(dto.getProductPrice()));
        entity.setRelatedPartyJson(writeJson(dto.getRelatedParty()));
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(ProductDto patch, Product entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getStatus() != null) {
            entity.setStatus(patch.getStatus());
        }
        if (patch.getTerminationDate() != null) {
            try {
                entity.setTerminationDate(java.time.OffsetDateTime.parse(patch.getTerminationDate()));
            } catch (Exception e) {
                // an unreadable date never corrupts the record
            }
        }
        if (patch.getProductOffering() != null) {
            // a REPOINTED offering is a plan change: remember what it was
            // and when, so the billing run can prorate the month honestly
            java.util.Map<String, Object> before = readObject(entity.getProductOfferingJson());
            Object oldId = before == null ? null : before.get("id");
            Object newId = patch.getProductOffering().get("id");
            if (oldId != null && newId != null && !String.valueOf(oldId).equals(String.valueOf(newId))) {
                entity.setPreviousOfferingJson(entity.getProductOfferingJson());
                entity.setOfferingChangedAt(java.time.OffsetDateTime.now());
            }
            entity.setProductOfferingJson(writeJson(patch.getProductOffering()));
        }
        if (patch.getBillingAccount() != null) {
            entity.setBillingAccountJson(writeJson(patch.getBillingAccount()));
        }
        if (patch.getProductCharacteristic() != null) {
            entity.setProductCharacteristicJson(writeJson(patch.getProductCharacteristic()));
        }
        if (patch.getProductPrice() != null) {
            entity.setProductPriceJson(writeJson(patch.getProductPrice()));
        }
        if (patch.getRelatedParty() != null) {
            entity.setRelatedPartyJson(writeJson(patch.getRelatedParty()));
        }
    }

    private List<Map<String, Object>> orEmpty(List<Map<String, Object>> value) {
        return value != null ? value : List.of();
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

    private Map<String, Object> readObject(String json) {
        return read(json, JSON_OBJECT);
    }

    private List<Map<String, Object>> readArray(String json) {
        return read(json, JSON_ARRAY);
    }

    private <T> T read(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON is unreadable", e);
        }
    }
}
