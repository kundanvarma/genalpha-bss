package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.BillingAccountDto;
import com.bss.party.entity.BillingAccount;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.BillingAccountMapper;
import com.bss.party.repository.BillingAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BillingAccountService {

    private static final String RESOURCE = "BillingAccount";

    private final BillingAccountRepository repository;
    private final BillingAccountMapper mapper;

    public BillingAccountService(BillingAccountRepository repository, BillingAccountMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<BillingAccountDto> findAll(int offset, int limit) {
        Page<BillingAccount> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public BillingAccountDto findById(String id) {
        BillingAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public BillingAccountDto create(BillingAccountDto dto) {
        BillingAccount entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.ACCOUNT_BASE + "/billingAccount/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public BillingAccountDto patch(String id, BillingAccountDto patch) {
        BillingAccount entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw NotFoundException.forResource(RESOURCE, id);
        }
        repository.deleteById(id);
    }
}
