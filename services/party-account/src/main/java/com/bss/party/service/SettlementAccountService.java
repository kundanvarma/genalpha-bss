package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.SettlementAccountDto;
import com.bss.party.entity.SettlementAccount;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.SettlementAccountMapper;
import com.bss.party.repository.SettlementAccountRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class SettlementAccountService {

    private static final String RESOURCE = "SettlementAccount";

    private final SettlementAccountRepository repository;
    private final SettlementAccountMapper mapper;
    private final DomainEventPublisher events;

    public SettlementAccountService(SettlementAccountRepository repository, SettlementAccountMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<SettlementAccountDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<SettlementAccount> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<SettlementAccount> probeFor(Map<String, String> filters) {
        SettlementAccount probe = new SettlementAccount();
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
    public SettlementAccountDto findById(String id) {
        SettlementAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public SettlementAccountDto create(SettlementAccountDto dto) {
        SettlementAccount entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/settlementAccount/" + id);
        SettlementAccountDto created = mapper.toDto(repository.save(entity));
        events.publish("SettlementAccountCreateEvent", "settlementAccount", created);
        return created;
    }

    @Transactional
    public SettlementAccountDto patch(String id, SettlementAccountDto patch) {
        SettlementAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        SettlementAccountDto updated = mapper.toDto(repository.save(entity));
        events.publish("SettlementAccountAttributeValueChangeEvent", "settlementAccount", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        SettlementAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        SettlementAccountDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("SettlementAccountDeleteEvent", "settlementAccount", deleted);
    }
}
