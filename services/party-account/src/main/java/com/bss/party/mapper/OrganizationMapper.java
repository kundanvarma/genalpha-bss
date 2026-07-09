package com.bss.party.mapper;

import com.bss.party.dto.OrganizationDto;
import com.bss.party.entity.Organization;
import org.springframework.stereotype.Component;

@Component
public class OrganizationMapper {

    public OrganizationDto toDto(Organization entity) {
        OrganizationDto dto = new OrganizationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setTradingName(entity.getTradingName());
        dto.setType("Organization");
        return dto;
    }

    public Organization toEntity(OrganizationDto dto) {
        Organization entity = new Organization();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setTradingName(dto.getTradingName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(OrganizationDto patch, Organization entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getTradingName() != null) {
            entity.setTradingName(patch.getTradingName());
        }
    }
}
