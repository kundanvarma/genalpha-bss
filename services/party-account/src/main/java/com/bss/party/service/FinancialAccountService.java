package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.FinancialAccountDto;
import com.bss.party.entity.FinancialAccount;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.FinancialAccountMapper;
import com.bss.party.repository.FinancialAccountRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class FinancialAccountService {

    private static final String RESOURCE = "FinancialAccount";

    private final FinancialAccountRepository repository;
    private final FinancialAccountMapper mapper;
    private final DomainEventPublisher events;

    public FinancialAccountService(FinancialAccountRepository repository, FinancialAccountMapper mapper, DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<FinancialAccountDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<FinancialAccount> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<FinancialAccount> probeFor(Map<String, String> filters) {
        FinancialAccount probe = new FinancialAccount();
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
    public FinancialAccountDto findById(String id) {
        FinancialAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public FinancialAccountDto create(FinancialAccountDto dto) {
        FinancialAccount entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/financialAccount/" + id);
        FinancialAccountDto created = mapper.toDto(repository.save(entity));
        events.publish("FinancialAccountCreateEvent", "financialAccount", created);
        return created;
    }

    @Transactional
    public FinancialAccountDto patch(String id, FinancialAccountDto patch) {
        FinancialAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        FinancialAccountDto updated = mapper.toDto(repository.save(entity));
        events.publish("FinancialAccountAttributeValueChangeEvent", "financialAccount", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        FinancialAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        FinancialAccountDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("FinancialAccountDeleteEvent", "financialAccount", deleted);
    }
}
