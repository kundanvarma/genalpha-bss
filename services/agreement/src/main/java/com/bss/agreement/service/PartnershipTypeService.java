package com.bss.agreement.service;

import com.bss.agreement.dto.PartnershipTypeRequest;
import com.bss.agreement.dto.PartnershipTypeView;
import com.bss.agreement.dto.RoleType;
import com.bss.agreement.entity.PartnershipType;
import com.bss.agreement.exception.BadRequestException;
import com.bss.agreement.exception.NotFoundException;
import com.bss.agreement.repository.PartnershipTypeRepository;
import com.bss.agreement.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TMF668: partnership kinds as tenant catalog data. A type names the roles
 * it permits; the agreement create path asks {@link #permittedRoles} when a
 * partnership names its type — the one validation the fleet never had.
 */
@Service
public class PartnershipTypeService {

    private static final String BASE = "/tmf-api/partnershipTypeManagement/v4";
    private static final TypeReference<List<RoleType>> ROLE_LIST = new TypeReference<>() {
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
    public List<PartnershipTypeView> findAll() {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PartnershipTypeView findById(String id) {
        return toView(require(id));
    }

    @Transactional
    public PartnershipTypeView create(PartnershipTypeRequest dto) {
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        // A kind without roles is a valid (if inert) catalog entry: it
        // permits NOTHING, and the agreement-signature check will refuse any
        // role named against it. But a roleType entry WITHOUT a name is a
        // contradiction — a role is its name.
        List<RoleType> roleTypes = List.of();
        if (dto.roleType() != null && dto.roleType().isArray() && !dto.roleType().isEmpty()) {
            roleTypes = sanitizeRoleTypes(dto.roleType());
            if (roleTypes.isEmpty()) {
                throw new BadRequestException(
                        "each roleType entry needs a name — a role IS its name");
            }
        }
        PartnershipType entity = new PartnershipType();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(BASE + "/partnershipType/" + id);
        entity.setName(dto.name());
        entity.setDescription(dto.description());
        entity.setStatus(dto.status() == null ? PartnershipType.ACTIVE : dto.status());
        entity.setRoleTypeJson(writeJson(roleTypes));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
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
                        .map(RoleType::name).toList())
                .orElse(List.of());
    }

    /* ---------- internals ---------- */

    private PartnershipType require(String id) {
        return repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PartnershipType", id));
    }

    private List<RoleType> sanitizeRoleTypes(JsonNode raw) {
        List<RoleType> out = new ArrayList<>();
        for (JsonNode entry : raw) {
            JsonNode name = entry.get("name");
            if (entry.isObject() && name != null && !name.isNull() && !name.asText().isBlank()) {
                JsonNode description = entry.get("description");
                out.add(RoleType.of(name.asText(),
                        description == null || description.isNull() ? null : description.asText()));
            }
        }
        return out;
    }

    private PartnershipTypeView toView(PartnershipType entity) {
        return PartnershipTypeView.of(entity.getId(), entity.getHref(), entity.getName(),
                entity.getDescription(), entity.getStatus(),
                readRoleTypes(entity.getRoleTypeJson()), entity.getLastUpdate());
    }

    private List<RoleType> readRoleTypes(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, ROLE_LIST);
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
