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
        dto.setBillingAnchorDay(entity.getBillingAnchorDay());
        dto.setBillDelivery(entity.getBillDelivery());
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setGivenName(entity.getGivenName());
        dto.setFamilyName(entity.getFamilyName());
        dto.setContactMedium(readJsonObjectList(entity.getContactMediumJson()));
        if (entity.getBirthDate() != null) {
            dto.setBirthDate(entity.getBirthDate().toString());
        }
        if (entity.getOrganizationId() != null) {
            dto.setOrganization(java.util.Map.of("id", entity.getOrganizationId(), "@referredType", "Organization"));
        }
        dto.setType("Individual");
        if (entity.getHouseholdPayerId() != null) {
            java.util.Map<String, Object> payer = new java.util.LinkedHashMap<>();
            payer.put("id", entity.getHouseholdPayerId());
            payer.put("status", entity.getHouseholdStatus());
            payer.put("role", entity.getHouseholdRole());
            if (entity.getTopupAllowanceValue() != null) {
                payer.put("topupAllowance", entity.getTopupAllowanceValue());
            }
            dto.setHouseholdPayer(payer);
        }
        return dto;
    }

    public Individual toEntity(IndividualDto dto) {
        Individual entity = new Individual();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setGivenName(dto.getGivenName());
        entity.setFamilyName(dto.getFamilyName());
        entity.setContactMediumJson(writeJsonObjectList(dto.getContactMedium()));
        if (dto.getBirthDate() != null && !dto.getBirthDate().isBlank()) {
            entity.setBirthDate(java.time.LocalDate.parse(dto.getBirthDate()));
        }
        if (dto.getOrganization() != null && dto.getOrganization().get("id") != null) {
            entity.setOrganizationId(String.valueOf(dto.getOrganization().get("id")));
        }
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
        if (patch.getBirthDate() != null && !patch.getBirthDate().isBlank()) {
            entity.setBirthDate(java.time.LocalDate.parse(patch.getBirthDate()));
        }
        if (patch.getOrganization() != null && patch.getOrganization().get("id") != null) {
            entity.setOrganizationId(String.valueOf(patch.getOrganization().get("id")));
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
