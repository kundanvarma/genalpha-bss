package com.bss.party.service;

import com.bss.party.entity.PartyRole;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.repository.PartyRoleRepository;
import com.bss.party.security.PartyScope;
import com.bss.party.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF669: the roles a party plays toward the operator. 'customer' is minted
 * automatically at self-registration; 'partner', 'supplier' and friends are
 * back-office grants. Customers read their own roles; staff manage all of
 * their tenant's.
 */
@Service
public class PartyRoleService {

    private static final String RESOURCE = "PartyRole";
    private static final String BASE_PATH = "/tmf-api/partyRoleManagement/v4";

    private final PartyRoleRepository repository;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public PartyRoleService(PartyRoleRepository repository, PartyScope partyScope, TenantScope tenantScope) {
        this.repository = repository;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    /** Idempotent: a party holds each role name at most once per tenant. */
    @Transactional
    public Map<String, Object> grant(String partyId, String roleName) {
        String tenant = tenantScope.currentTenantId();
        if (repository.existsByTenantIdAndPartyIdAndName(tenant, partyId, roleName)) {
            return repository.findByTenantIdAndPartyId(tenant, partyId).stream()
                    .filter(r -> roleName.equals(r.getName()))
                    .findFirst().map(this::toMap).orElseThrow();
        }
        PartyRole entity = new PartyRole();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setHref(BASE_PATH + "/partyRole/" + id);
        entity.setName(roleName);
        entity.setPartyId(partyId);
        entity.setStatus(PartyRole.ACTIVE);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        // TMF669: engagedParty is optional. When present, keep the idempotent
        // grant; otherwise create a standalone role (with its roleType echoed).
        Object party = dto.get("engagedParty");
        String partyId = party instanceof Map<?, ?> p && p.get("id") != null ? String.valueOf(p.get("id")) : null;
        String name = dto.get("name") == null ? null : String.valueOf(dto.get("name"));
        if (partyId != null && name != null) {
            Map<String, Object> granted = grant(partyId, name);
            if (dto.get("roleType") != null) {
                PartyRole e = repository.findByIdAndTenantId(String.valueOf(granted.get("id")),
                        tenantScope.currentTenantId()).orElseThrow();
                e.setRoleType(writeJson(dto.get("roleType")));
                return toMap(repository.save(e));
            }
            return granted;
        }
        PartyRole entity = new PartyRole();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(BASE_PATH + "/partyRole/" + id);
        entity.setName(name);
        entity.setPartyId(partyId);
        entity.setRoleType(writeJson(dto.get("roleType")));
        entity.setStatus(PartyRole.ACTIVE);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> dto) {
        PartyRole entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (dto.containsKey("name")) {
            entity.setName(String.valueOf(dto.get("name")));
        }
        if (dto.containsKey("status")) {
            entity.setStatus(String.valueOf(dto.get("status")));
        }
        if (dto.containsKey("roleType")) {
            entity.setRoleType(writeJson(dto.get("roleType")));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(Map<String, String> filters) {
        String tenant = tenantScope.currentTenantId();
        List<PartyRole> rows;
        String scoped = partyScope.scopedPartyId().orElse(filters.get("engagedPartyId"));
        if (scoped != null) {
            rows = repository.findByTenantIdAndPartyId(tenant, scoped);
        } else {
            rows = repository.findByTenantId(tenant);
        }
        return rows.stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("href") == null || filters.get("href").equals(r.getHref()))
                .filter(r -> filters.get("name") == null || filters.get("name").equals(r.getName()))
                .filter(r -> filters.get("status") == null || filters.get("status").equals(r.getStatus()))
                .map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        PartyRole entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getPartyId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        return toMap(entity);
    }

    @Transactional
    public void delete(String id) {
        PartyRole entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if ("customer".equals(entity.getName())) {
            throw new BadRequestException("the customer role is lifecycle-managed, not deletable");
        }
        repository.delete(entity);
    }

    private Map<String, Object> toMap(PartyRole r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("href", r.getHref());
        map.put("name", r.getName());
        map.put("status", r.getStatus());
        map.put("roleType", readJson(r.getRoleType()));
        if (r.getPartyId() != null) {
            map.put("engagedParty", Map.of("id", r.getPartyId(), "@referredType", "Individual"));
        }
        map.put("@type", "PartyRole");
        return map;
    }

    private String writeJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
