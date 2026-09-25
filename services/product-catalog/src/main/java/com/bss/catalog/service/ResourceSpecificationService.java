package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ResourceSpecificationDto;
import com.bss.catalog.entity.ResourceSpecification;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ResourceSpecificationMapper;
import com.bss.catalog.repository.ResourceSpecificationRepository;
import com.bss.catalog.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Service
public class ResourceSpecificationService {

    private static final String RESOURCE = "ResourceSpecification";

    private final ResourceSpecificationRepository repository;
    private final ResourceSpecificationMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ResourceSpecificationService(ResourceSpecificationRepository repository, ResourceSpecificationMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ResourceSpecificationDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ResourceSpecification> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    private Example<ResourceSpecification> probeFor(Map<String, String> filters) {
        ResourceSpecification probe = new ResourceSpecification();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "category" -> probe.setCategory(f.getValue());
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
    public ResourceSpecificationDto findById(String id) {
        ResourceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ResourceSpecificationDto create(ResourceSpecificationDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ResourceSpecification entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.RESOURCE_CATALOG_BASE_PATH + "/resourceSpecification/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ResourceSpecificationDto created = mapper.toDto(repository.save(entity));
        events.publish("ResourceSpecificationCreateEvent", "resourceSpecification", created);
        return created;
    }

    @Transactional
    public ResourceSpecificationDto patch(String id, ResourceSpecificationDto patch) {
        if (patch.getId() != null || patch.getHref() != null) {
            // TMF630: id and href are server-assigned — a patch that names them is malformed
            throw new BadRequestException("id and href are read-only and cannot be patched");
        }
        ResourceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ResourceSpecificationDto updated = mapper.toDto(repository.save(entity));
        events.publish("ResourceSpecificationAttributeValueChangeEvent", "resourceSpecification", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ResourceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ResourceSpecificationDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ResourceSpecificationDeleteEvent", "resourceSpecification", deleted);
    }
}
