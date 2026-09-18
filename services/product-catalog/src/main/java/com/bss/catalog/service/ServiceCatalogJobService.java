package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ServiceCatalogJobDto;
import com.bss.catalog.entity.ServiceCatalogJob;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ServiceCatalogJobMapper;
import com.bss.catalog.repository.ServiceCatalogJobRepository;
import com.bss.catalog.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * TMF633 ImportJob and ExportJob, one service parameterised by kind. A job is
 * accepted and recorded ("Not Started"); nothing yet moves catalog data to or
 * from the url — the record is the honest contract until a runner exists.
 */
@Service
public class ServiceCatalogJobService {

    private static final String NOT_STARTED = "Not Started";

    private final ServiceCatalogJobRepository repository;
    private final ServiceCatalogJobMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ServiceCatalogJobService(ServiceCatalogJobRepository repository, ServiceCatalogJobMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ServiceCatalogJobDto> findAll(String kind, int offset, int limit, Map<String, String> filters) {
        Page<ServiceCatalogJob> page = repository.findAll(probeFor(kind, filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    private Example<ServiceCatalogJob> probeFor(String kind, Map<String, String> filters) {
        ServiceCatalogJob probe = new ServiceCatalogJob();
        probe.setTenantId(tenantScope.currentTenantId());
        probe.setKind(kind);
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "url" -> probe.setUrl(f.getValue());
                case "path" -> probe.setPath(f.getValue());
                case "contentType" -> probe.setContentType(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "creationDate" -> {
                    try {
                        probe.setCreationDate(OffsetDateTime.parse(f.getValue()));
                    } catch (DateTimeParseException e) {
                        throw new BadRequestException("creationDate filter is not a valid date-time");
                    }
                }
                default -> {
                    if (ServiceCatalogJob.EXPORT.equals(kind) && "query".equals(f.getKey())) {
                        probe.setQuery(f.getValue());
                    } else {
                        throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
                    }
                }
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public ServiceCatalogJobDto findById(String kind, String id) {
        return mapper.toDto(find(kind, id));
    }

    @Transactional
    public ServiceCatalogJobDto create(String kind, ServiceCatalogJobDto dto) {
        ServiceCatalogJob entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setKind(kind);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.SERVICE_CATALOG_BASE_PATH + "/" + resourceName(kind) + "/" + id);
        entity.setStatus(NOT_STARTED);
        entity.setCompletionDate(null);
        entity.setErrorLog(null);
        entity.setCreationDate(OffsetDateTime.now());
        if (ServiceCatalogJob.IMPORT.equals(kind)) {
            entity.setQuery(null);
        }
        ServiceCatalogJobDto created = mapper.toDto(repository.save(entity));
        events.publish(created.getType() + "CreateEvent", resourceName(kind), created);
        return created;
    }

    @Transactional
    public void delete(String kind, String id) {
        ServiceCatalogJob entity = find(kind, id);
        ServiceCatalogJobDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish(deleted.getType() + "DeleteEvent", resourceName(kind), deleted);
    }

    private ServiceCatalogJob find(String kind, String id) {
        return repository.findByIdAndTenantIdAndKind(id, tenantScope.currentTenantId(), kind)
                .orElseThrow(() -> NotFoundException.forResource(
                        ServiceCatalogJob.EXPORT.equals(kind) ? "ExportJob" : "ImportJob", id));
    }

    static String resourceName(String kind) {
        return ServiceCatalogJob.EXPORT.equals(kind) ? "exportJob" : "importJob";
    }
}
