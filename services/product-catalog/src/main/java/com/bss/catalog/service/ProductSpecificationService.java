package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.entity.ProductSpecification;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductSpecificationMapper;
import com.bss.catalog.repository.ProductSpecificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductSpecificationService {

    private static final String RESOURCE = "ProductSpecification";

    private final ProductSpecificationRepository repository;
    private final ProductSpecificationMapper mapper;

    public ProductSpecificationService(ProductSpecificationRepository repository,
                                       ProductSpecificationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductSpecificationDto> findAll(int offset, int limit) {
        Page<ProductSpecification> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductSpecificationDto findById(String id) {
        ProductSpecification entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductSpecificationDto create(ProductSpecificationDto dto) {
        ProductSpecification entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productSpecification/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public ProductSpecificationDto patch(String id, ProductSpecificationDto patch) {
        ProductSpecification entity = repository.findById(id)
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
