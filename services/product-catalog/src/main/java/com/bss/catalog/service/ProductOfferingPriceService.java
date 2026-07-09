package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.entity.ProductOfferingPrice;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingPriceMapper;
import com.bss.catalog.repository.ProductOfferingPriceRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductOfferingPriceService {

    private static final String RESOURCE = "ProductOfferingPrice";

    private final ProductOfferingPriceRepository repository;
    private final ProductOfferingPriceMapper mapper;
    private final DomainEventPublisher events;

    public ProductOfferingPriceService(ProductOfferingPriceRepository repository,
            ProductOfferingPriceMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOfferingPriceDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductOfferingPrice> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<ProductOfferingPrice> probeFor(Map<String, String> filters) {
        ProductOfferingPrice probe = new ProductOfferingPrice();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "priceType" -> probe.setPriceType(f.getValue());
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
    public ProductOfferingPriceDto findById(String id) {
        ProductOfferingPrice entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOfferingPriceDto create(ProductOfferingPriceDto dto) {
        if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        ProductOfferingPrice entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOfferingPrice/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductOfferingPriceDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingPriceCreateEvent", "productOfferingPrice", created);
        return created;
    }

    @Transactional
    public ProductOfferingPriceDto patch(String id, ProductOfferingPriceDto patch) {
        ProductOfferingPrice entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductOfferingPriceDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductOfferingPriceAttributeValueChangeEvent", "productOfferingPrice", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductOfferingPrice entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOfferingPriceDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("ProductOfferingPriceDeleteEvent", "productOfferingPrice", deleted);
    }
}
