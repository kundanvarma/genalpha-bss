package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingMapper;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.repository.ProductOfferingRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductOfferingService {

    private static final String RESOURCE = "ProductOffering";

    private final ProductOfferingRepository repository;
    private final ProductOfferingMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ProductOfferingService(ProductOfferingRepository repository, ProductOfferingMapper mapper, DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOfferingDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductOffering> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<ProductOffering> probeFor(Map<String, String> filters) {
        ProductOffering probe = new ProductOffering();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "version" -> probe.setVersion(f.getValue());
                case "isBundle" -> {
                    if (!"true".equals(f.getValue()) && !"false".equals(f.getValue())) {
                        throw new BadRequestException("isBundle filter must be 'true' or 'false'");
                    }
                    probe.setIsBundle(Boolean.valueOf(f.getValue()));
                }
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
    public ProductOfferingDto findById(String id) {
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOfferingDto create(ProductOfferingDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ProductOffering entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/productOffering/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductOfferingDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingCreateEvent", "productOffering", created);
        return created;
    }

    @Transactional
    public ProductOfferingDto patch(String id, ProductOfferingDto patch) {
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductOfferingDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingAttributeValueChangeEvent", "productOffering", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOfferingDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ProductOfferingDeleteEvent", "productOffering", deleted);
    }
}
