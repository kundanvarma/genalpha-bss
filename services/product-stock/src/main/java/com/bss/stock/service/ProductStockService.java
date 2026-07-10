package com.bss.stock.service;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.api.OffsetPageRequest;
import com.bss.stock.api.PagedResult;
import com.bss.stock.dto.ProductStockDto;
import com.bss.stock.entity.ProductStock;
import com.bss.stock.events.DomainEventPublisher;
import com.bss.stock.exception.BadRequestException;
import com.bss.stock.exception.NotFoundException;
import com.bss.stock.mapper.ProductStockMapper;
import com.bss.stock.repository.ProductStockRepository;
import com.bss.stock.repository.StockReservationRepository;
import com.bss.stock.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductStockService {

    private static final String RESOURCE = "ProductStock";

    private final ProductStockRepository repository;
    private final StockReservationRepository reservations;
    private final ProductStockMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ProductStockService(ProductStockRepository repository, StockReservationRepository reservations,
            ProductStockMapper mapper, DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.reservations = reservations;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    private ProductStockDto toDto(ProductStock entity) {
        return mapper.toDto(entity, reservations.activeQuantityFor(entity.getId(), tenantScope.currentTenantId()));
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductStockDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductStock> page = repository.findAll(Example.of(probeFor(filters)),
                new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private ProductStock probeFor(Map<String, String> filters) {
        ProductStock probe = new ProductStock();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public ProductStockDto findById(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return toDto(entity);
    }

    @Transactional
    public ProductStockDto create(ProductStockDto dto) {
        ProductStock entity = mapper.toEntity(dto);
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productStock/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductStockDto created = toDto(repository.save(entity));
        events.publish("ProductStockCreateEvent", "productStock", created);
        return created;
    }

    @Transactional
    public ProductStockDto patch(String id, ProductStockDto patch) {
        ProductStock entity = repository.findForUpdateById(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductStockDto updated = toDto(repository.save(entity));
        events.publish("ProductStockAttributeValueChangeEvent", "productStock", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductStockDto deleted = toDto(entity);
        repository.delete(entity);
        events.publish("ProductStockDeleteEvent", "productStock", deleted);
    }
}
