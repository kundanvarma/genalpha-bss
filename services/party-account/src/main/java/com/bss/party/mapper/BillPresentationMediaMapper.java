package com.bss.party.mapper;

import com.bss.party.dto.BillPresentationMediaDto;
import com.bss.party.entity.BillPresentationMedia;
import org.springframework.stereotype.Component;

@Component
public class BillPresentationMediaMapper {

    public BillPresentationMediaDto toDto(BillPresentationMedia entity) {
        BillPresentationMediaDto dto = new BillPresentationMediaDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setType("BillPresentationMedia");
        return dto;
    }

    public BillPresentationMedia toEntity(BillPresentationMediaDto dto) {
        BillPresentationMedia entity = new BillPresentationMedia();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(BillPresentationMediaDto patch, BillPresentationMedia entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
    }
}
