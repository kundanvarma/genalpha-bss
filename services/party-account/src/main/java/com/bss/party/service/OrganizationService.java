package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.OrganizationDto;
import com.bss.party.entity.Organization;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.OrganizationMapper;
import com.bss.party.repository.OrganizationRepository;
import com.bss.party.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class OrganizationService {

    private static final String RESOURCE = "Organization";

    private final OrganizationRepository repository;
    private final OrganizationMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public OrganizationService(OrganizationRepository repository, OrganizationMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<OrganizationDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<Organization> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<Organization> probeFor(Map<String, String> filters) {
        Organization probe = new Organization();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "tradingName" -> probe.setTradingName(f.getValue());
                case "parentId" -> probe.setParentId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public OrganizationDto findById(String id) {
        Organization entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public OrganizationDto create(OrganizationDto dto) {
        Organization entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.PARTY_BASE + "/organization/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        OrganizationDto created = mapper.toDto(repository.save(entity));
        events.publish("OrganizationCreateEvent", "organization", created);
        return created;
    }

    @Transactional
    public OrganizationDto patch(String id, OrganizationDto patch) {
        Organization entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        OrganizationDto updated = mapper.toDto(repository.save(entity));
        events.publish("OrganizationAttributeValueChangeEvent", "organization", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Organization entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        OrganizationDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("OrganizationDeleteEvent", "organization", deleted);
    }
}
