package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ServiceCandidateDto;
import com.bss.catalog.entity.ServiceCandidate;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ServiceCandidateMapper;
import com.bss.catalog.repository.ServiceCandidateRepository;
import com.bss.catalog.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/** TMF633 ServiceCandidate — tenant-scoped CRUD in the ServiceSpecification style. */
@Service
public class ServiceCandidateService {

    private static final String RESOURCE = "ServiceCandidate";

    private final ServiceCandidateRepository repository;
    private final ServiceCandidateMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ServiceCandidateService(ServiceCandidateRepository repository, ServiceCandidateMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ServiceCandidateDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ServiceCandidate> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    private Example<ServiceCandidate> probeFor(Map<String, String> filters) {
        ServiceCandidate probe = new ServiceCandidate();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "version" -> probe.setVersion(f.getValue());
                case "lastUpdate" -> {
                    try {
                        probe.setLastUpdate(OffsetDateTime.parse(f.getValue()));
                    } catch (DateTimeParseException e) {
                        throw new BadRequestException("lastUpdate filter is not a valid date-time");
                    }
                }
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public ServiceCandidateDto findById(String id) {
        ServiceCandidate entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ServiceCandidateDto create(ServiceCandidateDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ServiceCandidate entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.SERVICE_CATALOG_BASE_PATH + "/serviceCandidate/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ServiceCandidateDto created = mapper.toDto(repository.save(entity));
        events.publish("ServiceCandidateCreateEvent", "serviceCandidate", created);
        return created;
    }

    @Transactional
    public ServiceCandidateDto patch(String id, ServiceCandidateDto patch) {
        if (patch.getId() != null || patch.getHref() != null) {
            // TMF630: id and href are server-assigned — a patch that names them is malformed
            throw new BadRequestException("id and href are read-only and cannot be patched");
        }
        ServiceCandidate entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ServiceCandidateDto updated = mapper.toDto(repository.save(entity));
        events.publish("ServiceCandidateAttributeValueChangeEvent", "serviceCandidate", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ServiceCandidate entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ServiceCandidateDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ServiceCandidateDeleteEvent", "serviceCandidate", deleted);
    }
}
