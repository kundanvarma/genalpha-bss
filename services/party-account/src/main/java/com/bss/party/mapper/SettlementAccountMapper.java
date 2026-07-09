package com.bss.party.mapper;

import com.bss.party.dto.SettlementAccountDto;
import com.bss.party.entity.SettlementAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SettlementAccountMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public SettlementAccountMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SettlementAccountDto toDto(SettlementAccount entity) {
        SettlementAccountDto dto = new SettlementAccountDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setRelatedParty(readJsonArray(entity.getRelatedPartyJson()));
        dto.setType("SettlementAccount");
        return dto;
    }

    public SettlementAccount toEntity(SettlementAccountDto dto) {
        SettlementAccount entity = new SettlementAccount();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setRelatedPartyJson(writeJsonArray(dto.getRelatedParty()));
        return entity;
    }

    /**
     * Applies non-null fields of the patch DTO onto the entity (JSON merge patch style).
     */
    public void applyPatch(SettlementAccountDto patch, SettlementAccount entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
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
