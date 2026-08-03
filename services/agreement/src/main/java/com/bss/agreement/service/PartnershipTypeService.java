package com.bss.agreement.service;

import com.bss.agreement.entity.PartnershipType;
import com.bss.agreement.exception.BadRequestException;
import com.bss.agreement.exception.NotFoundException;
import com.bss.agreement.repository.PartnershipTypeRepository;
import com.bss.agreement.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF668: partnership kinds as tenant catalog data. A type names the roles
 * it permits; the agreement create path asks {@link #permittedRoles} when a
 * partnership names its type — the one validation the fleet never had.
 */
@Service
public class PartnershipTypeService {

    private static final String BASE = "/tmf-api/partnershipTypeManagement/v4";
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final PartnershipTypeRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public PartnershipTypeService(PartnershipTypeRepository repository, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(require(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || String.valueOf(dto.get("name")).isBlank()) {
            throw new BadRequestException("name is required");
        }
        List<Map<String, Object>> roleTypes = dto.get("roleType") instanceof List<?> list
                ? sanitizeRoleTypes(list) : List.of();
        if (roleTypes.isEmpty()) {
            throw new BadRequestException(
                    "roleType is required — a partnership kind IS the roles it permits");
        }
        PartnershipType entity = new PartnershipType();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(BASE + "/partnershipType/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setDescription(dto.get("description") == null ? null
                : String.valueOf(dto.get("description")));
        entity.setStatus(dto.get("status") == null ? PartnershipType.ACTIVE
                : String.valueOf(dto.get("status")));
        entity.setRoleTypeJson(writeJson(roleTypes));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional
    public void delete(String id) {
        repository.delete(require(id));
    }

    /** The roles this type permits — the agreement create path's question.
     * Empty when the type is unknown or retired (the caller refuses then). */
    @Transactional(readOnly = true)
    public List<String> permittedRoles(String typeId) {
        return repository.findByIdAndTenantId(typeId, tenantScope.currentTenantId())
                .filter(t -> PartnershipType.ACTIVE.equals(t.getStatus()))
                .map(t -> readRoleTypes(t.getRoleTypeJson()).stream()
                        .map(r -> String.valueOf(r.get("name"))).toList())
                .orElse(List.of());
    }

    /* ---------- internals ---------- */

    private PartnershipType require(String id) {
        return repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PartnershipType", id));
    }

    private List<Map<String, Object>> sanitizeRoleTypes(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : raw) {
            if (entry instanceof Map<?, ?> m && m.get("name") != null
                    && !String.valueOf(m.get("name")).isBlank()) {
                Map<String, Object> role = new LinkedHashMap<>();
                role.put("name", String.valueOf(m.get("name")));
                if (m.get("description") != null) {
                    role.put("description", String.valueOf(m.get("description")));
                }
                role.put("@type", "PartnerRoleType");
                out.add(role);
            }
        }
        return out;
    }

    private Map<String, Object> toMap(PartnershipType entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("name", entity.getName());
        if (entity.getDescription() != null) {
            map.put("description", entity.getDescription());
        }
        map.put("status", entity.getStatus());
        map.put("roleType", readRoleTypes(entity.getRoleTypeJson()));
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "PartnershipType");
        return map;
    }

    private List<Map<String, Object>> readRoleTypes(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, JSON_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored role types are unreadable", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }
}
