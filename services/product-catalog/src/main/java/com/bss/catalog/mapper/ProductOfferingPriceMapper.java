package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.entity.ProductOfferingPrice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProductOfferingPriceMapper {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<java.util.List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductOfferingPriceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductOfferingPriceDto toDto(ProductOfferingPrice entity) {
        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setPriceType(entity.getPriceType());
        dto.setIsBundle(entity.getIsBundle());
        dto.setPrice(readJsonObject(entity.getPriceJson()));
        dto.setProdSpecCharValueUse(readJsonList(entity.getProdSpecCharValueUseJson()));
        dto.setTax(readJsonList(entity.getTaxJson()));
        dto.setRecurringChargePeriodType(entity.getRecurringChargePeriodType());
        dto.setRecurringChargePeriodLength(entity.getRecurringChargePeriodLength());
        if (entity.getValidFrom() != null || entity.getValidTo() != null) {
            Map<String, Object> window = new java.util.LinkedHashMap<>();
            if (entity.getValidFrom() != null) {
                window.put("startDateTime", entity.getValidFrom());
            }
            if (entity.getValidTo() != null) {
                window.put("endDateTime", entity.getValidTo());
            }
            dto.setValidFor(window);
        }
        dto.setUnitOfMeasure(readJsonObject(entity.getUnitOfMeasureJson()));
        dto.setPricingLogicAlgorithm(readJsonList(entity.getPricingLogicAlgorithmJson()));
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setVersion(entity.getVersion());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("ProductOfferingPrice");
        return dto;
    }

    public ProductOfferingPrice toEntity(ProductOfferingPriceDto dto) {
        ProductOfferingPrice entity = new ProductOfferingPrice();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setPriceType(dto.getPriceType());
        entity.setIsBundle(dto.getIsBundle());
        entity.setPriceJson(writeJsonObject(dto.getPrice()));
        entity.setProdSpecCharValueUseJson(writeJsonList(dto.getProdSpecCharValueUse()));
        entity.setTaxJson(writeJsonList(dto.getTax()));
        entity.setRecurringChargePeriodType(dto.getRecurringChargePeriodType());
        entity.setRecurringChargePeriodLength(dto.getRecurringChargePeriodLength());
        applyWindow(dto.getValidFor(), entity);
        entity.setUnitOfMeasureJson(writeJsonObject(dto.getUnitOfMeasure()));
        entity.setPricingLogicAlgorithmJson(writeJsonList(dto.getPricingLogicAlgorithm()));
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setVersion(dto.getVersion());
        entity.setLastUpdate(dto.getLastUpdate());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(ProductOfferingPriceDto patch, ProductOfferingPrice entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getPriceType() != null) {
            entity.setPriceType(patch.getPriceType());
        }
        if (patch.getIsBundle() != null) {
            entity.setIsBundle(patch.getIsBundle());
        }
        if (patch.getPrice() != null) {
            entity.setPriceJson(writeJsonObject(patch.getPrice()));
        }
        if (patch.getValidFor() != null) {
            applyWindow(patch.getValidFor(), entity);
        }
        if (patch.getUnitOfMeasure() != null) {
            entity.setUnitOfMeasureJson(writeJsonObject(patch.getUnitOfMeasure()));
        }
        if (patch.getPricingLogicAlgorithm() != null) {
            entity.setPricingLogicAlgorithmJson(writeJsonList(patch.getPricingLogicAlgorithm()));
        }
        if (patch.getTax() != null) {
            entity.setTaxJson(writeJsonList(patch.getTax()));
        }
        if (patch.getProdSpecCharValueUse() != null) {
            entity.setProdSpecCharValueUseJson(writeJsonList(patch.getProdSpecCharValueUse()));
        }
        if (patch.getRecurringChargePeriodType() != null) {
            entity.setRecurringChargePeriodType(patch.getRecurringChargePeriodType());
        }
        if (patch.getRecurringChargePeriodLength() != null) {
            entity.setRecurringChargePeriodLength(patch.getRecurringChargePeriodLength());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getVersion() != null) {
            entity.setVersion(patch.getVersion());
        }
    }

    private String writeJsonList(java.util.List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON list", e);
        }
    }

    private java.util.List<Map<String, Object>> readJsonList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON list is unreadable", e);
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

    /** TMF620 validFor {startDateTime, endDateTime} → the two columns; an absent bound stays open. */
    private static void applyWindow(Map<String, Object> window, ProductOfferingPrice entity) {
        if (window == null) {
            return;
        }
        entity.setValidFrom(parseTime(window.get("startDateTime")));
        entity.setValidTo(parseTime(window.get("endDateTime")));
    }

    private static java.time.OffsetDateTime parseTime(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof java.time.OffsetDateTime t) {
            return t;
        }
        try {
            return java.time.OffsetDateTime.parse(String.valueOf(v));
        } catch (Exception e) {
            return java.time.LocalDate.parse(String.valueOf(v).substring(0, 10)).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        }
    }
}
