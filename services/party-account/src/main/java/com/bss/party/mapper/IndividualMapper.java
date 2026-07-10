package com.bss.party.mapper;

import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IndividualMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public IndividualMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IndividualDto toDto(Individual entity) {
        IndividualDto dto = new IndividualDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setGivenName(entity.getGivenName());
        dto.setFamilyName(entity.getFamilyName());
        dto.setContactMedium(readJsonObjectList(entity.getContactMediumJson()));
        dto.setType("Individual");
        return dto;
    }

    public Individual toEntity(IndividualDto dto) {
        Individual entity = new Individual();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setGivenName(dto.getGivenName());
        entity.setFamilyName(dto.getFamilyName());
        entity.setContactMediumJson(writeJsonObjectList(dto.getContactMedium()));
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
        if (patch.getContactMedium() != null) {
            entity.setContactMediumJson(writeJsonObjectList(patch.getContactMedium()));
        }
    }

    private String writeJsonObjectList(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonObjectList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
