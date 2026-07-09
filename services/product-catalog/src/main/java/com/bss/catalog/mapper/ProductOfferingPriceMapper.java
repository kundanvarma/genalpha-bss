package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.entity.ProductOfferingPrice;
import org.springframework.stereotype.Component;

@Component
public class ProductOfferingPriceMapper {

    public ProductOfferingPriceDto toDto(ProductOfferingPrice entity) {
        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setPriceType(entity.getPriceType());
        dto.setIsBundle(entity.getIsBundle());
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
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getVersion() != null) {
            entity.setVersion(patch.getVersion());
        }
    }
}
