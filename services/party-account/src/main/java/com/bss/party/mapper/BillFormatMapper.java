package com.bss.party.mapper;

import com.bss.party.dto.BillFormatDto;
import com.bss.party.entity.BillFormat;
import org.springframework.stereotype.Component;

@Component
public class BillFormatMapper {

    public BillFormatDto toDto(BillFormat entity) {
        BillFormatDto dto = new BillFormatDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setType("BillFormat");
        return dto;
    }

    public BillFormat toEntity(BillFormatDto dto) {
        BillFormat entity = new BillFormat();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(BillFormatDto patch, BillFormat entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
    }
}
