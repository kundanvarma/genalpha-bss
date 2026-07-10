package com.bss.inventory.service;

import com.bss.inventory.api.ApiConstants;
import com.bss.inventory.api.OffsetPageRequest;
import com.bss.inventory.api.PagedResult;
import com.bss.inventory.dto.ProductDto;
import com.bss.inventory.entity.Product;
import com.bss.inventory.events.DomainEventPublisher;
import com.bss.inventory.exception.BadRequestException;
import com.bss.inventory.exception.NotFoundException;
import com.bss.inventory.mapper.ProductMapper;
import com.bss.inventory.repository.ProductRepository;
import com.bss.inventory.security.PartyScope;
import com.bss.inventory.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private static final String RESOURCE = "Product";

    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public ProductService(ProductRepository repository, ProductMapper mapper,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductDto> findAll(int offset, int limit, Map<String, String> filters) {
        Product probe = probeFor(filters);
        probe.setTenantId(tenantScope.currentTenantId());
        // Customers see their own products only, whatever else they filter on.
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Product> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Product probeFor(Map<String, String> filters) {
        Product probe = new Product();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public ProductDto findById(String id) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductDto create(ProductDto dto) {
        if (dto.getStatus() == null) {
            dto.setStatus("created");
        }
        Product entity = mapper.toEntity(dto);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElseGet(() -> customerPartyIn(dto.getRelatedParty())));
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/product/" + id);
        ProductDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductCreateEvent", "product", created);
        return created;
    }

    @Transactional
    public ProductDto patch(String id, ProductDto patch) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        mapper.applyPatch(patch, entity);
        ProductDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductAttributeValueChangeEvent", "product", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        ProductDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ProductDeleteEvent", "product", deleted);
    }

    /**
     * Scoped tokens address only their own products; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(Product entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    /**
     * Owner of a machine- or staff-created product (order provisioning sends
     * the order's relatedParty): the party in the customer role, if any.
     */
    private String customerPartyIn(List<Map<String, Object>> relatedParty) {
        if (relatedParty == null) {
            return null;
        }
        return relatedParty.stream()
                .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                .map(p -> String.valueOf(p.get("id")))
                .findFirst()
                .orElse(null);
    }
}
