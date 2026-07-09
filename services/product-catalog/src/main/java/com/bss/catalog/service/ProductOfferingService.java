package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingMapper;
import com.bss.catalog.repository.ProductOfferingRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductOfferingService {

    private static final String RESOURCE = "ProductOffering";

    private final ProductOfferingRepository repository;
    private final ProductOfferingMapper mapper;
    private final DomainEventPublisher events;

    public ProductOfferingService(ProductOfferingRepository repository, ProductOfferingMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOfferingDto> findAll(int offset, int limit) {
        Page<ProductOffering> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductOfferingDto findById(String id) {
        ProductOffering entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOfferingDto create(ProductOfferingDto dto) {
        ProductOffering entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOffering/" + id);
        ProductOfferingDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingCreateEvent", "productOffering", created);
        return created;
    }

    @Transactional
    public ProductOfferingDto patch(String id, ProductOfferingDto patch) {
        ProductOffering entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        ProductOfferingDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingAttributeValueChangeEvent", "productOffering", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductOffering entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOfferingDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("ProductOfferingDeleteEvent", "productOffering", deleted);
    }
}
