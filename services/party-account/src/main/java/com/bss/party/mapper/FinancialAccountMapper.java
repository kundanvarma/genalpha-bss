package com.bss.party.mapper;

import com.bss.party.dto.FinancialAccountDto;
import com.bss.party.entity.FinancialAccount;
import org.springframework.stereotype.Component;

@Component
public class FinancialAccountMapper {

    public FinancialAccountDto toDto(FinancialAccount entity) {
        FinancialAccountDto dto = new FinancialAccountDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setType("FinancialAccount");
        return dto;
    }

    public FinancialAccount toEntity(FinancialAccountDto dto) {
        FinancialAccount entity = new FinancialAccount();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(FinancialAccountDto patch, FinancialAccount entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
    }
}
