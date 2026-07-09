package com.bss.ordering.mapper;

import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductOrderMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public ProductOrderMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductOrderDto toDto(ProductOrder entity) {
        ProductOrderDto dto = new ProductOrderDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setState(entity.getState());
        dto.setDescription(entity.getDescription());
        dto.setCategory(entity.getCategory());
        dto.setProductOfferingId(entity.getProductOfferingId());
        dto.setBillingAccountId(entity.getBillingAccountId());
        dto.setProductOrderItem(readJsonArray(entity.getProductOrderItemJson()));
        dto.setRelatedParty(readJsonArray(entity.getRelatedPartyJson()));
        dto.setOrderDate(entity.getOrderDate());
        dto.setType("ProductOrder");
        return dto;
    }

    public ProductOrder toEntity(ProductOrderDto dto) {
        ProductOrder entity = new ProductOrder();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setState(dto.getState());
        entity.setDescription(dto.getDescription());
        entity.setCategory(dto.getCategory());
        entity.setProductOfferingId(dto.getProductOfferingId());
        entity.setBillingAccountId(dto.getBillingAccountId());
        entity.setProductOrderItemJson(writeJsonArray(dto.getProductOrderItem()));
        entity.setRelatedPartyJson(writeJsonArray(dto.getRelatedParty()));
        entity.setOrderDate(dto.getOrderDate());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(ProductOrderDto patch, ProductOrder entity) {
        if (patch.getState() != null) {
            entity.setState(patch.getState());
        }
        if (patch.getDescription() != null) {
            entity.setDescription(patch.getDescription());
        }
        if (patch.getCategory() != null) {
            entity.setCategory(patch.getCategory());
        }
        if (patch.getProductOfferingId() != null) {
            entity.setProductOfferingId(patch.getProductOfferingId());
        }
        if (patch.getBillingAccountId() != null) {
            entity.setBillingAccountId(patch.getBillingAccountId());
        }
        if (patch.getProductOrderItem() != null) {
            entity.setProductOrderItemJson(writeJsonArray(patch.getProductOrderItem()));
        }
        if (patch.getRelatedParty() != null) {
            entity.setRelatedPartyJson(writeJsonArray(patch.getRelatedParty()));
        }
        if (patch.getOrderDate() != null) {
            entity.setOrderDate(patch.getOrderDate());
        }
    }

    private String writeJsonArray(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonArray(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_ARRAY);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
