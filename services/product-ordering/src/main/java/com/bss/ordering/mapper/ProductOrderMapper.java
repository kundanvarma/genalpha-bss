package com.bss.ordering.mapper;

import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import org.springframework.stereotype.Component;

@Component
public class ProductOrderMapper {

    public ProductOrderDto toDto(ProductOrder entity) {
        ProductOrderDto dto = new ProductOrderDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setState(entity.getState());
        dto.setDescription(entity.getDescription());
        dto.setCategory(entity.getCategory());
        dto.setProductOfferingId(entity.getProductOfferingId());
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
        if (patch.getOrderDate() != null) {
            entity.setOrderDate(patch.getOrderDate());
        }
    }
}
