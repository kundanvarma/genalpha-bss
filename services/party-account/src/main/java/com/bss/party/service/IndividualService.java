package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.IndividualMapper;
import com.bss.party.repository.IndividualRepository;
import com.bss.party.security.TenantScope;
import com.bss.party.security.PartyScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class IndividualService {

    private static final String RESOURCE = "Individual";

    private final IndividualRepository repository;
    private final IndividualMapper mapper;
    private final PartyRoleService partyRoles;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public IndividualService(IndividualRepository repository, IndividualMapper mapper,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope, PartyRoleService partyRoles) {
        this.repository = repository;
        this.mapper = mapper;
        this.partyRoles = partyRoles;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<IndividualDto> findAll(int offset, int limit, Map<String, String> filters) {
        Individual probe = probeFor(filters);
        // A business admin lists people, but only ever their own organization's.
        businessAdminOrg().ifPresent(probe::setOrganizationId);
        // A customer sees exactly one individual: their own.
        partyScope.scopedPartyId().ifPresent(probe::setId);
        Page<Individual> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Individual probeFor(Map<String, String> filters) {
        Individual probe = new Individual();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "familyName" -> probe.setFamilyName(f.getValue());
                case "givenName" -> probe.setGivenName(f.getValue());
                case "organizationId" -> probe.setOrganizationId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public IndividualDto findById(String id) {
        requireOwn(id);
        Individual entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    /**
     * A customer's individual id IS their token subject, so self-registration
     * needs no id hand-shake: the first create after signup provisions the
     * party, and repeating it returns the existing record (idempotent).
     */
    @Transactional
    public IndividualDto create(IndividualDto dto) {
        // Customers are keyed to their token subject. Unscoped callers (staff
        // provisioning an org admin) may pin the id to the person's IdP subject.
        String id = partyScope.scopedPartyId()
                .orElseGet(() -> dto.getId() != null && !dto.getId().isBlank()
                        ? dto.getId() : UUID.randomUUID().toString());
        Individual existing = repository.findByIdAndTenantId(id, tenantScope.currentTenantId()).orElse(null);
        if (existing != null) {
            return mapper.toDto(existing);
        }
        Individual entity = mapper.toEntity(dto);
        entity.setId(id);
        entity.setHref(ApiConstants.PARTY_BASE + "/individual/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        // A business admin's new person always belongs to the admin's own org.
        businessAdminOrg().ifPresent(entity::setOrganizationId);
        IndividualDto created = mapper.toDto(repository.save(entity));
        // TMF669: self-registered parties are customers by definition; other
        // roles (partner, supplier) are back-office grants. A business admin's
        // members are customers of the operator too.
        if (partyScope.scopedPartyId().isPresent() || businessAdminOrg().isPresent()) {
            partyRoles.grant(id, "customer");
        }
        events.publish("IndividualCreateEvent", "individual", created);
        return created;
    }

    @Transactional
    public IndividualDto patch(String id, IndividualDto patch) {
        requireOwn(id);
        if (patch.getOrganization() != null && patch.getOrganization().get("id") != null) {
            requireOwnOrg(String.valueOf(patch.getOrganization().get("id")));
        }
        Individual entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        IndividualDto updated = mapper.toDto(repository.save(entity));
        events.publish("IndividualAttributeValueChangeEvent", "individual", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        requireOwn(id);
        Individual entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        IndividualDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("IndividualDeleteEvent", "individual", deleted);
    }

    /**
     * Scoped tokens address only their own individual; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(String id) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(id)) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
    }

    /* ---------------- B2B org boundary for business admins ---------------- */

    /** The caller's organization id when they are a business:admin, else empty. */
    private java.util.Optional<String> businessAdminOrg() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "business:admin".equals(a.getAuthority()))) {
            return java.util.Optional.empty();
        }
        return repository.findByIdAndTenantId(auth.getName(), tenantScope.currentTenantId())
                .map(Individual::getOrganizationId)
                .filter(java.util.Objects::nonNull)
                .map(java.util.Optional::of)
                .orElseThrow(() -> new BadRequestException(
                        "business admin has no organization membership"));
    }

    /** Business admins may only touch their own organization's people. */
    private void requireOwnOrg(String organizationId) {
        businessAdminOrg().ifPresent(own -> {
            if (!own.equals(organizationId)) {
                throw new BadRequestException("outside your organization");
            }
        });
    }
}
