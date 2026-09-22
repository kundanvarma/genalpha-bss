package com.bss.catalog.mapper;

import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.Quantity;
import com.bss.catalog.dto.TimePeriod;
import com.bss.catalog.entity.ProductOfferingPrice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ProductOfferingPriceMapper {

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
        dto.setPrice(readJson(entity.getPriceJson(), Money.class));
        dto.setProdSpecCharValueUse(readJsonList(entity.getProdSpecCharValueUseJson()));
        dto.setTax(readJsonList(entity.getTaxJson()));
        dto.setRecurringChargePeriodType(entity.getRecurringChargePeriodType());
        dto.setRecurringChargePeriodLength(entity.getRecurringChargePeriodLength());
        if (entity.getValidFrom() != null || entity.getValidTo() != null) {
            dto.setValidFor(new TimePeriod(entity.getValidFrom(), entity.getValidTo()));
        }
        dto.setUnitOfMeasure(readJson(entity.getUnitOfMeasureJson(), Quantity.class));
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
        entity.setPriceJson(writeJson(dto.getPrice()));
        entity.setProdSpecCharValueUseJson(writeJson(dto.getProdSpecCharValueUse()));
        entity.setTaxJson(writeJson(dto.getTax()));
        entity.setRecurringChargePeriodType(dto.getRecurringChargePeriodType());
        entity.setRecurringChargePeriodLength(dto.getRecurringChargePeriodLength());
        applyWindow(dto.getValidFor(), entity);
        entity.setUnitOfMeasureJson(writeJson(dto.getUnitOfMeasure()));
        entity.setPricingLogicAlgorithmJson(writeJson(dto.getPricingLogicAlgorithm()));
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
            entity.setPriceJson(writeJson(patch.getPrice()));
        }
        if (patch.getValidFor() != null) {
            applyWindow(patch.getValidFor(), entity);
        }
        if (patch.getUnitOfMeasure() != null) {
            entity.setUnitOfMeasureJson(writeJson(patch.getUnitOfMeasure()));
        }
        if (patch.getPricingLogicAlgorithm() != null) {
            entity.setPricingLogicAlgorithmJson(writeJson(patch.getPricingLogicAlgorithm()));
        }
        if (patch.getTax() != null) {
            entity.setTaxJson(writeJson(patch.getTax()));
        }
        if (patch.getProdSpecCharValueUse() != null) {
            entity.setProdSpecCharValueUseJson(writeJson(patch.getProdSpecCharValueUse()));
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

    private <T> T readJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON object is unreadable", e);
        }
    }

    /** TMF620 validFor {startDateTime, endDateTime} → the two columns; an absent bound stays open. */
    private static void applyWindow(TimePeriod window, ProductOfferingPrice entity) {
        if (window == null) {
            return;
        }
        entity.setValidFrom(window.startDateTime());
        entity.setValidTo(window.endDateTime());
    }
}
