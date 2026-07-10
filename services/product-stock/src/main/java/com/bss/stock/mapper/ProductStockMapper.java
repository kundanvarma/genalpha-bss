package com.bss.stock.mapper;

import com.bss.stock.dto.ProductStockDto;
import com.bss.stock.dto.QuantityDto;
import com.bss.stock.entity.ProductStock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProductStockMapper {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductStockMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** reservedActive is supplied by the service (sum of active reservations). */
    public ProductStockDto toDto(ProductStock entity, int reservedActive) {
        ProductStockDto dto = new ProductStockDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setProductOffering(readJsonObject(entity.getProductOfferingJson()));
        String units = entity.getStockedUnits();
        dto.setStockedQuantity(new QuantityDto(entity.getStockedAmount(), units));
        dto.setReservedQuantity(new QuantityDto(reservedActive, units));
        dto.setAvailableQuantity(new QuantityDto(entity.getStockedAmount() - reservedActive, units));
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("ProductStock");
        return dto;
    }

    public ProductStock toEntity(ProductStockDto dto) {
        ProductStock entity = new ProductStock();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setProductOfferingJson(writeJsonObject(dto.getProductOffering()));
        entity.setProductOfferingId(offeringIdIn(dto));
        if (dto.getStockedQuantity() != null) {
            entity.setStockedAmount(dto.getStockedQuantity().getAmount());
            entity.setStockedUnits(dto.getStockedQuantity().getUnits());
        }
        entity.setLastUpdate(dto.getLastUpdate());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(ProductStockDto patch, ProductStock entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getProductOffering() != null) {
            entity.setProductOfferingJson(writeJsonObject(patch.getProductOffering()));
            entity.setProductOfferingId(offeringIdIn(patch));
        }
        if (patch.getStockedQuantity() != null) {
            entity.setStockedAmount(patch.getStockedQuantity().getAmount());
            entity.setStockedUnits(patch.getStockedQuantity().getUnits());
        }
    }

    private String offeringIdIn(ProductStockDto dto) {
        Object id = dto.getProductOffering() == null ? null : dto.getProductOffering().get("id");
        return id == null ? null : String.valueOf(id);
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
