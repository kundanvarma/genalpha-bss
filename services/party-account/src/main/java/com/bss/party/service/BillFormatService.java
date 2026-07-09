package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.BillFormatDto;
import com.bss.party.entity.BillFormat;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.BillFormatMapper;
import com.bss.party.repository.BillFormatRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class BillFormatService {

    private static final String RESOURCE = "BillFormat";

    private final BillFormatRepository repository;
    private final BillFormatMapper mapper;
    private final DomainEventPublisher events;

    public BillFormatService(BillFormatRepository repository, BillFormatMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<BillFormatDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<BillFormat> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<BillFormat> probeFor(Map<String, String> filters) {
        BillFormat probe = new BillFormat();
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
    public BillFormatDto findById(String id) {
        BillFormat entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public BillFormatDto create(BillFormatDto dto) {
        BillFormat entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/billFormat/" + id);
        BillFormatDto created = mapper.toDto(repository.save(entity));
        events.publish("BillFormatCreateEvent", "billFormat", created);
        return created;
    }

    @Transactional
    public BillFormatDto patch(String id, BillFormatDto patch) {
        BillFormat entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        BillFormatDto updated = mapper.toDto(repository.save(entity));
        events.publish("BillFormatAttributeValueChangeEvent", "billFormat", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        BillFormat entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        BillFormatDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("BillFormatDeleteEvent", "billFormat", deleted);
    }
}
