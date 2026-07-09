package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.PartyAccountDto;
import com.bss.party.entity.PartyAccount;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.PartyAccountMapper;
import com.bss.party.repository.PartyAccountRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class PartyAccountService {

    private static final String RESOURCE = "PartyAccount";

    private final PartyAccountRepository repository;
    private final PartyAccountMapper mapper;
    private final DomainEventPublisher events;

    public PartyAccountService(PartyAccountRepository repository, PartyAccountMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<PartyAccountDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<PartyAccount> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<PartyAccount> probeFor(Map<String, String> filters) {
        PartyAccount probe = new PartyAccount();
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
    public PartyAccountDto findById(String id) {
        PartyAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public PartyAccountDto create(PartyAccountDto dto) {
        PartyAccount entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/partyAccount/" + id);
        PartyAccountDto created = mapper.toDto(repository.save(entity));
        events.publish("PartyAccountCreateEvent", "partyAccount", created);
        return created;
    }

    @Transactional
    public PartyAccountDto patch(String id, PartyAccountDto patch) {
        PartyAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        PartyAccountDto updated = mapper.toDto(repository.save(entity));
        events.publish("PartyAccountAttributeValueChangeEvent", "partyAccount", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        PartyAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        PartyAccountDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("PartyAccountDeleteEvent", "partyAccount", deleted);
    }
}
