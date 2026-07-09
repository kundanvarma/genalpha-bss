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
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private static final String RESOURCE = "Product";

    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final DomainEventPublisher events;

    public ProductService(ProductRepository repository, ProductMapper mapper,
            DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<Product> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<Product> probeFor(Map<String, String> filters) {
        Product probe = new Product();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public ProductDto findById(String id) {
        Product entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductDto create(ProductDto dto) {
        if (dto.getStatus() == null) {
            dto.setStatus("created");
        }
        Product entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/product/" + id);
        ProductDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductCreateEvent", "product", created);
        return created;
    }

    @Transactional
    public ProductDto patch(String id, ProductDto patch) {
        Product entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        ProductDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductAttributeValueChangeEvent", "product", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Product entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("ProductDeleteEvent", "product", deleted);
    }
}
