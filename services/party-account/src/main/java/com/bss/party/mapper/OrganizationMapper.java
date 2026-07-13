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
        if (entity.getParentId() != null) {
            dto.setParentOrganization(java.util.Map.of("id", entity.getParentId(), "@referredType", "Organization"));
        }
        if (entity.getDeviceAllowanceValue() != null) {
            dto.setDeviceAllowance(java.util.Map.of(
                    "value", entity.getDeviceAllowanceValue(),
                    "unit", entity.getDeviceAllowanceUnit() == null ? "EUR" : entity.getDeviceAllowanceUnit()));
        }
        dto.setType("Organization");
        return dto;
    }

    public Organization toEntity(OrganizationDto dto) {
        Organization entity = new Organization();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setTradingName(dto.getTradingName());
        if (dto.getParentOrganization() != null && dto.getParentOrganization().get("id") != null) {
            entity.setParentId(String.valueOf(dto.getParentOrganization().get("id")));
        }
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(OrganizationDto patch, Organization entity) {
        if (patch.getParentOrganization() != null && patch.getParentOrganization().get("id") != null) {
            entity.setParentId(String.valueOf(patch.getParentOrganization().get("id")));
        }
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getTradingName() != null) {
            entity.setTradingName(patch.getTradingName());
        }
        if (patch.getDeviceAllowance() != null) {
            Object value = patch.getDeviceAllowance().get("value");
            entity.setDeviceAllowanceValue(value == null ? null
                    : new java.math.BigDecimal(String.valueOf(value)));
            Object unit = patch.getDeviceAllowance().get("unit");
            entity.setDeviceAllowanceUnit(unit == null ? null : String.valueOf(unit));
        }
    }
}
