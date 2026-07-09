package com.bss.party.mapper;

import com.bss.party.dto.BillingAccountDto;
import com.bss.party.entity.BillingAccount;
import org.springframework.stereotype.Component;

@Component
public class BillingAccountMapper {

    public BillingAccountDto toDto(BillingAccount entity) {
        BillingAccountDto dto = new BillingAccountDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setState(entity.getState());
        dto.setRelatedPartyId(entity.getRelatedPartyId());
        dto.setType("BillingAccount");
        return dto;
    }

    public BillingAccount toEntity(BillingAccountDto dto) {
        BillingAccount entity = new BillingAccount();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setState(dto.getState());
        entity.setRelatedPartyId(dto.getRelatedPartyId());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(BillingAccountDto patch, BillingAccount entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getState() != null) {
            entity.setState(patch.getState());
        }
        if (patch.getRelatedPartyId() != null) {
            entity.setRelatedPartyId(patch.getRelatedPartyId());
        }
    }
}
