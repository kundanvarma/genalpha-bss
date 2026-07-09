package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.BillingCycleSpecificationDto;
import com.bss.party.entity.BillingCycleSpecification;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.BillingCycleSpecificationMapper;
import com.bss.party.repository.BillingCycleSpecificationRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class BillingCycleSpecificationService {

    private static final String RESOURCE = "BillingCycleSpecification";

    private final BillingCycleSpecificationRepository repository;
    private final BillingCycleSpecificationMapper mapper;
    private final DomainEventPublisher events;

    public BillingCycleSpecificationService(BillingCycleSpecificationRepository repository, BillingCycleSpecificationMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<BillingCycleSpecificationDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<BillingCycleSpecification> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<BillingCycleSpecification> probeFor(Map<String, String> filters) {
        BillingCycleSpecification probe = new BillingCycleSpecification();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public BillingCycleSpecificationDto findById(String id) {
        BillingCycleSpecification entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public BillingCycleSpecificationDto create(BillingCycleSpecificationDto dto) {
        BillingCycleSpecification entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/billingCycleSpecification/" + id);
        BillingCycleSpecificationDto created = mapper.toDto(repository.save(entity));
        events.publish("BillingCycleSpecificationCreateEvent", "billingCycleSpecification", created);
        return created;
    }

    @Transactional
    public BillingCycleSpecificationDto patch(String id, BillingCycleSpecificationDto patch) {
        BillingCycleSpecification entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        BillingCycleSpecificationDto updated = mapper.toDto(repository.save(entity));
        events.publish("BillingCycleSpecificationAttributeValueChangeEvent", "billingCycleSpecification", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        BillingCycleSpecification entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        BillingCycleSpecificationDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("BillingCycleSpecificationDeleteEvent", "billingCycleSpecification", deleted);
    }
}
