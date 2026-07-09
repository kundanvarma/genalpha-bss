package com.bss.party.mapper;

import com.bss.party.dto.BillingAccountDto;
import com.bss.party.entity.BillingAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BillingAccountMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public BillingAccountMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BillingAccountDto toDto(BillingAccount entity) {
        BillingAccountDto dto = new BillingAccountDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setState(entity.getState());
        dto.setRelatedPartyId(entity.getRelatedPartyId());
        dto.setRelatedParty(readJsonArray(entity.getRelatedPartyJson()));
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
        entity.setRelatedPartyJson(writeJsonArray(dto.getRelatedParty()));
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
        if (patch.getRelatedParty() != null) {
            entity.setRelatedPartyJson(writeJsonArray(patch.getRelatedParty()));
        }
    }

    private String writeJsonArray(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonArray(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_ARRAY);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
