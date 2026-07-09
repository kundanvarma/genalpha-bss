package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.IndividualMapper;
import com.bss.party.repository.IndividualRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IndividualService {

    private static final String RESOURCE = "Individual";

    private final IndividualRepository repository;
    private final IndividualMapper mapper;
    private final DomainEventPublisher events;

    public IndividualService(IndividualRepository repository, IndividualMapper mapper,
            DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<IndividualDto> findAll(int offset, int limit) {
        Page<Individual> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public IndividualDto findById(String id) {
        Individual entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public IndividualDto create(IndividualDto dto) {
        Individual entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.PARTY_BASE + "/individual/" + id);
        IndividualDto created = mapper.toDto(repository.save(entity));
        events.publish("IndividualCreateEvent", "individual", created);
        return created;
    }

    @Transactional
    public IndividualDto patch(String id, IndividualDto patch) {
        Individual entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        IndividualDto updated = mapper.toDto(repository.save(entity));
        events.publish("IndividualAttributeValueChangeEvent", "individual", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Individual entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        IndividualDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("IndividualDeleteEvent", "individual", deleted);
    }
}
