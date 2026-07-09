package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
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

    public IndividualService(IndividualRepository repository, IndividualMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
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
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public IndividualDto patch(String id, IndividualDto patch) {
        Individual entity = repository.findById(id)
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
