package com.bss.party.service;

import com.bss.party.dto.EntityRef;
import com.bss.party.dto.PartyRoleRequest;
import com.bss.party.dto.PartyRoleView;
import com.bss.party.entity.PartyRole;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.repository.PartyRoleRepository;
import com.bss.party.security.PartyScope;
import com.bss.party.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
    public PartyRoleView grant(String partyId, String roleName) {
        String tenant = tenantScope.currentTenantId();
        if (repository.existsByTenantIdAndPartyIdAndName(tenant, partyId, roleName)) {
            return repository.findByTenantIdAndPartyId(tenant, partyId).stream()
                    .filter(r -> roleName.equals(r.getName()))
                    .findFirst().map(this::toView).orElseThrow();
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
        return toView(repository.save(entity));
    }

    @Transactional
    public PartyRoleView create(PartyRoleRequest dto) {
        // TMF669: engagedParty is optional. When present, keep the idempotent
        // grant; otherwise create a standalone role (with its roleType echoed).
        String partyId = dto.engagedPartyId();
        String name = dto.name();
        if (partyId != null && name != null) {
            PartyRoleView granted = grant(partyId, name);
            if (dto.hasRoleType()) {
                PartyRole e = repository.findByIdAndTenantId(granted.id(), tenantScope.currentTenantId())
                        .orElseThrow();
                e.setRoleType(writeJson(dto.roleType()));
                return toView(repository.save(e));
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
        entity.setRoleType(writeJson(dto.roleType()));
        entity.setStatus(PartyRole.ACTIVE);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    /** JSON merge patch on the three mutable fields; an explicit {@code roleType: null} clears it. */
    @Transactional
    public PartyRoleView patch(String id, PartyRoleRequest dto) {
        PartyRole entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (dto.name() != null) {
            entity.setName(dto.name());
        }
        if (dto.status() != null) {
            entity.setStatus(dto.status());
        }
        if (dto.roleType() != null) {
            entity.setRoleType(writeJson(dto.roleType()));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<PartyRoleView> findAll(Map<String, String> filters) {
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
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PartyRoleView findById(String id) {
        PartyRole entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getPartyId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        return toView(entity);
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

    private PartyRoleView toView(PartyRole r) {
        return new PartyRoleView(r.getId(), r.getHref(), r.getName(), r.getStatus(), readJson(r.getRoleType()),
                r.getPartyId() == null ? null : EntityRef.individual(r.getPartyId()), "PartyRole");
    }

    /** The caller's open roleType block, stored as written; an explicit null clears the column. */
    private String writeJson(JsonNode o) {
        if (o == null || o.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readJson(String s) {
        if (s == null || s.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
