package com.bss.party.service;

import com.bss.party.api.ApiConstants;
import com.bss.party.dto.OrganizationDto;
import com.bss.party.entity.Organization;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.OrganizationMapper;
import com.bss.party.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    public List<OrganizationDto> findAll() {
        return repository.findAll().stream().map(mapper::toDto).toList();
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
