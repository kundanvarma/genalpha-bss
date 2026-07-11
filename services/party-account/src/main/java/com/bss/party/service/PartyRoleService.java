package com.bss.party.service;

import com.bss.party.entity.PartyRole;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.repository.PartyRoleRepository;
import com.bss.party.security.PartyScope;
import com.bss.party.security.TenantScope;
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
        if (dto.get("name") == null || !(dto.get("engagedParty") instanceof Map<?, ?> party)
                || party.get("id") == null) {
            throw new BadRequestException("name and engagedParty.id are required");
        }
        return grant(String.valueOf(party.get("id")), String.valueOf(dto.get("name")));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String requestedPartyId) {
        String party = partyScope.scopedPartyId().orElse(requestedPartyId);
        if (party == null) {
            throw new BadRequestException("engagedPartyId is required for unscoped callers");
        }
        return repository.findByTenantIdAndPartyId(tenantScope.currentTenantId(), party)
                .stream().map(this::toMap).toList();
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
        map.put("engagedParty", Map.of("id", r.getPartyId(), "@referredType", "Individual"));
        map.put("@type", "PartyRole");
        return map;
    }
}
