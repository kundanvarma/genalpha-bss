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

    /**
     * THE PROTECTED-ADDRESS MASK (Norway rails, kode 6/7 shape). For a party
     * with {@code addressProtected}, these keys are removed from every
     * contact-medium entry — both at the entry's top level and inside its
     * {@code characteristic} — on EVERY read, staff and customer alike.
     * What survives on purpose: postal code + city (parcels route to a
     * pickup point), email, phone. The same key set is scrubbed from
     * STORAGE the moment the flag flips (never cache what you may not hold).
     */
    private static final java.util.Set<String> STREET_KEYS = java.util.Set.of(
            "street1", "street2", "streetname", "streetnr", "streetnumber",
            "streetsuffix", "streettype", "address1", "address2",
            "addressline1", "addressline2", "fulladdress", "geographicaddress");

    private static final ObjectMapper STATIC_JSON = new ObjectMapper();

    private final ObjectMapper objectMapper;

    public IndividualMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // region is a plain single-valued attribute — mapped both ways + on patch.
    public IndividualDto toDto(Individual entity) {
        IndividualDto dto = new IndividualDto();
        dto.setBillingAnchorDay(entity.getBillingAnchorDay());
        dto.setBillDelivery(entity.getBillDelivery());
        dto.setRegion(entity.getRegion());
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setGivenName(entity.getGivenName());
        dto.setFamilyName(entity.getFamilyName());
        List<Map<String, Object>> media = readJsonObjectList(entity.getContactMediumJson());
        dto.setContactMedium(entity.isAddressProtected() ? maskStreetData(media) : media);
        if (entity.isAddressProtected()) {
            dto.setAddressProtected(Boolean.TRUE);
        }
        if (entity.isDeceased()) {
            dto.setDeceased(Boolean.TRUE);
        }
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
        entity.setRegion(dto.getRegion());
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
        if (patch.getRegion() != null) {
            entity.setRegion(patch.getRegion());
        }
        if (patch.getBirthDate() != null && !patch.getBirthDate().isBlank()) {
            entity.setBirthDate(java.time.LocalDate.parse(patch.getBirthDate()));
        }
        if (patch.getOrganization() != null && patch.getOrganization().get("id") != null) {
            entity.setOrganizationId(String.valueOf(patch.getOrganization().get("id")));
        }
    }

    /* -------- protected-address helpers (Norway rails; see STREET_KEYS) -------- */

    /** Read-time mask: deep-copies the entries with street keys removed. */
    public static List<Map<String, Object>> maskStreetData(List<Map<String, Object>> media) {
        if (media == null) {
            return null;
        }
        return media.stream().map(IndividualMapper::maskEntry).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> maskEntry(Map<String, Object> entry) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            if (STREET_KEYS.contains(e.getKey().toLowerCase())) {
                continue;
            }
            if (e.getValue() instanceof Map<?, ?> nested) {
                out.put(e.getKey(), maskEntry((Map<String, Object>) nested));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Storage scrub, applied when the protected flag flips ON: the stored
     * JSON itself loses the street data, so no search index, export or later
     * code path can resurrect it. */
    public static String scrubStreetData(String contactMediumJson) {
        if (contactMediumJson == null) {
            return null;
        }
        try {
            List<Map<String, Object>> media =
                    STATIC_JSON.readValue(contactMediumJson, JSON_OBJECT_LIST);
            return STATIC_JSON.writeValueAsString(maskStreetData(media));
        } catch (JsonProcessingException e) {
            // unreadable stored JSON may hide street data — drop it entirely
            return null;
        }
    }

    /** Registry-sync write: replace (or add) the postal contact medium with
     * the registry's registered address — {street1, postCode, city, country}
     * under {@code characteristic}, the same shape checkout writes. */
    public static String withRegisteredAddress(String contactMediumJson,
            Map<String, Object> registeredAddress) {
        if (registeredAddress == null) {
            return contactMediumJson;
        }
        List<Map<String, Object>> media;
        try {
            media = contactMediumJson == null ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(
                            STATIC_JSON.readValue(contactMediumJson, JSON_OBJECT_LIST));
        } catch (JsonProcessingException e) {
            media = new java.util.ArrayList<>();
        }
        Map<String, Object> characteristic = new java.util.LinkedHashMap<>(registeredAddress);
        Map<String, Object> postal = media.stream()
                .filter(IndividualMapper::isPostalEntry)
                .findFirst().orElse(null);
        if (postal == null) {
            postal = new java.util.LinkedHashMap<>();
            postal.put("mediumType", "postalAddress");
            media.add(postal);
        }
        postal.put("characteristic", characteristic);
        try {
            return STATIC_JSON.writeValueAsString(media);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable contact medium", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isPostalEntry(Map<String, Object> entry) {
        String mediumType = entry.get("mediumType") == null ? ""
                : String.valueOf(entry.get("mediumType")).toLowerCase();
        if (mediumType.contains("postal") || mediumType.contains("address")) {
            return true;
        }
        Map<String, Object> characteristic = entry.get("characteristic") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : Map.of();
        return characteristic.keySet().stream()
                .anyMatch(k -> STREET_KEYS.contains(k.toLowerCase()) || "city".equalsIgnoreCase(k));
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
