package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.BillingAccountDto;
import com.bss.party.entity.BillingAccount;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.BillingAccountMapper;
import com.bss.party.repository.BillingAccountRepository;
import com.bss.party.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class BillingAccountService {

    private static final String RESOURCE = "BillingAccount";

    private final BillingAccountRepository repository;
    private final BillingAccountMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public BillingAccountService(BillingAccountRepository repository, BillingAccountMapper mapper,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<BillingAccountDto> findAll(int offset, int limit, Map<String, String> filters) {
        Page<BillingAccount> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<BillingAccount> probeFor(Map<String, String> filters) {
        BillingAccount probe = new BillingAccount();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "state" -> probe.setState(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public BillingAccountDto findById(String id) {
        BillingAccount entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public BillingAccountDto create(BillingAccountDto dto) {
        BillingAccount entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/billingAccount/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        BillingAccountDto created = mapper.toDto(repository.save(entity));
        events.publish("BillingAccountCreateEvent", "billingAccount", created);
        return created;
    }

    @Transactional
    public BillingAccountDto patch(String id, BillingAccountDto patch) {
        BillingAccount entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        BillingAccountDto updated = mapper.toDto(repository.save(entity));
        events.publish("BillingAccountAttributeValueChangeEvent", "billingAccount", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        BillingAccount entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        BillingAccountDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("BillingAccountDeleteEvent", "billingAccount", deleted);
    }
}
