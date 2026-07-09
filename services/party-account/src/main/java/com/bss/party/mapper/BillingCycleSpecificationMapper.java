package com.bss.party.mapper;

import com.bss.party.dto.BillingCycleSpecificationDto;
import com.bss.party.entity.BillingCycleSpecification;
import org.springframework.stereotype.Component;

@Component
public class BillingCycleSpecificationMapper {

    public BillingCycleSpecificationDto toDto(BillingCycleSpecification entity) {
        BillingCycleSpecificationDto dto = new BillingCycleSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setType("BillingCycleSpecification");
        return dto;
    }

    public BillingCycleSpecification toEntity(BillingCycleSpecificationDto dto) {
        BillingCycleSpecification entity = new BillingCycleSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(BillingCycleSpecificationDto patch, BillingCycleSpecification entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
    }
}
