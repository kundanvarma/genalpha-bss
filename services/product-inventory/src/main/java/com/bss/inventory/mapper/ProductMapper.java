package com.bss.inventory.mapper;

import com.bss.inventory.dto.ProductDto;
import com.bss.inventory.entity.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public ProductDto toDto(Product entity) {
        ProductDto dto = new ProductDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setProductOfferingId(entity.getProductOfferingId());
        dto.setBillingAccountId(entity.getBillingAccountId());
        dto.setType("Product");
        return dto;
    }

    public Product toEntity(ProductDto dto) {
        Product entity = new Product();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setProductOfferingId(dto.getProductOfferingId());
        entity.setBillingAccountId(dto.getBillingAccountId());
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
        if (patch.getProductOfferingId() != null) {
            entity.setProductOfferingId(patch.getProductOfferingId());
        }
        if (patch.getBillingAccountId() != null) {
            entity.setBillingAccountId(patch.getBillingAccountId());
        }
    }
}
