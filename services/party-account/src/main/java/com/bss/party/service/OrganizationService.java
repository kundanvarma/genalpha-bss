package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.OffsetPageRequest;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.OrganizationDto;
import com.bss.party.entity.Organization;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.OrganizationMapper;
import com.bss.party.repository.OrganizationRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrganizationService {

    private static final String RESOURCE = "Organization";

    private final OrganizationRepository repository;
    private final OrganizationMapper mapper;

    public OrganizationService(OrganizationRepository repository, OrganizationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<OrganizationDto> findAll(int offset, int limit) {
        Page<Organization> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrganizationDto findById(String id) {
        Organization entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public OrganizationDto create(OrganizationDto dto) {
        Organization entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.PARTY_BASE + "/organization/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public OrganizationDto patch(String id, OrganizationDto patch) {
        Organization entity = repository.findById(id)
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
