package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.BillPresentationMediaDto;
import com.bss.party.entity.BillPresentationMedia;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.BillPresentationMediaMapper;
import com.bss.party.repository.BillPresentationMediaRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class BillPresentationMediaService {

    private static final String RESOURCE = "BillPresentationMedia";

    private final BillPresentationMediaRepository repository;
    private final BillPresentationMediaMapper mapper;
    private final DomainEventPublisher events;

    public BillPresentationMediaService(BillPresentationMediaRepository repository, BillPresentationMediaMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<BillPresentationMediaDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<BillPresentationMedia> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<BillPresentationMedia> probeFor(Map<String, String> filters) {
        BillPresentationMedia probe = new BillPresentationMedia();
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
    public BillPresentationMediaDto findById(String id) {
        BillPresentationMedia entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public BillPresentationMediaDto create(BillPresentationMediaDto dto) {
        BillPresentationMedia entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/billPresentationMedia/" + id);
        BillPresentationMediaDto created = mapper.toDto(repository.save(entity));
        events.publish("BillPresentationMediaCreateEvent", "billPresentationMedia", created);
        return created;
    }

    @Transactional
    public BillPresentationMediaDto patch(String id, BillPresentationMediaDto patch) {
        BillPresentationMedia entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        BillPresentationMediaDto updated = mapper.toDto(repository.save(entity));
        events.publish("BillPresentationMediaAttributeValueChangeEvent", "billPresentationMedia", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        BillPresentationMedia entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        BillPresentationMediaDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("BillPresentationMediaDeleteEvent", "billPresentationMedia", deleted);
    }
}
