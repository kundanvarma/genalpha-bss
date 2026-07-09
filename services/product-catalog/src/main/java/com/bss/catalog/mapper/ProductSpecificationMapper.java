package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.entity.ProductSpecification;
import org.springframework.stereotype.Component;

@Component
public class ProductSpecificationMapper {

    public ProductSpecificationDto toDto(ProductSpecification entity) {
        ProductSpecificationDto dto = new ProductSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setBrand(entity.getBrand());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setLastUpdate(entity.getLastUpdate());
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
    }
}
