package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.entity.ProductSpecification;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductSpecificationMapper;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.repository.ProductSpecificationRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductSpecificationService {

    private static final String RESOURCE = "ProductSpecification";

    private final ProductSpecificationRepository repository;
    private final ProductSpecificationMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ProductSpecificationService(ProductSpecificationRepository repository, ProductSpecificationMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductSpecificationDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductSpecification> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<ProductSpecification> probeFor(Map<String, String> filters) {
        ProductSpecification probe = new ProductSpecification();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "brand" -> probe.setBrand(f.getValue());
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
    public ProductSpecificationDto findById(String id) {
        ProductSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductSpecificationDto create(ProductSpecificationDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ProductSpecification entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/productSpecification/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductSpecificationDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductSpecificationCreateEvent", "productSpecification", created);
        return created;
    }

    @Transactional
    public ProductSpecificationDto patch(String id, ProductSpecificationDto patch) {
        ProductSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductSpecificationDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductSpecificationAttributeValueChangeEvent", "productSpecification", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductSpecification entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductSpecificationDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ProductSpecificationDeleteEvent", "productSpecification", deleted);
    }
}
