package com.bss.party.mapper;

import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
import org.springframework.stereotype.Component;

@Component
public class IndividualMapper {

    public IndividualDto toDto(Individual entity) {
        IndividualDto dto = new IndividualDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setGivenName(entity.getGivenName());
        dto.setFamilyName(entity.getFamilyName());
        dto.setType("Individual");
        return dto;
    }

    public Individual toEntity(IndividualDto dto) {
        Individual entity = new Individual();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setGivenName(dto.getGivenName());
        entity.setFamilyName(dto.getFamilyName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(IndividualDto patch, Individual entity) {
        if (patch.getGivenName() != null) {
            entity.setGivenName(patch.getGivenName());
        }
        if (patch.getFamilyName() != null) {
            entity.setFamilyName(patch.getFamilyName());
        }
    }
}
