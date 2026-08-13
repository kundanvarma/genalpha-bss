package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ServiceSpecificationDto;
import com.bss.catalog.entity.ServiceSpecification;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ServiceSpecificationMapper;
import com.bss.catalog.repository.ServiceSpecificationRepository;
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
public class ServiceSpecificationService {

    private static final String RESOURCE = "ServiceSpecification";

    private final ServiceSpecificationRepository repository;
    private final ServiceSpecificationMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ServiceSpecificationService(ServiceSpecificationRepository repository, ServiceSpecificationMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ServiceSpecificationDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ServiceSpecification> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    private Example<ServiceSpecification> probeFor(Map<String, String> filters) {
        ServiceSpecification probe = new ServiceSpecification();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "serviceType" -> probe.setServiceType(f.getValue());
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
    public ServiceSpecificationDto findById(String id) {
        ServiceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ServiceSpecificationDto create(ServiceSpecificationDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ServiceSpecification entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.SERVICE_CATALOG_BASE_PATH + "/serviceSpecification/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ServiceSpecificationDto created = mapper.toDto(repository.save(entity));
        events.publish("ServiceSpecificationCreateEvent", "serviceSpecification", created);
        return created;
    }

    @Transactional
    public ServiceSpecificationDto patch(String id, ServiceSpecificationDto patch) {
        ServiceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ServiceSpecificationDto updated = mapper.toDto(repository.save(entity));
        events.publish("ServiceSpecificationAttributeValueChangeEvent", "serviceSpecification", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ServiceSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ServiceSpecificationDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ServiceSpecificationDeleteEvent", "serviceSpecification", deleted);
    }
}
