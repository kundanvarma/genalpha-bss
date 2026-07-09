package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingMapper;
import com.bss.catalog.repository.ProductOfferingRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductOfferingService {

    private static final String RESOURCE = "ProductOffering";

    private final ProductOfferingRepository repository;
    private final ProductOfferingMapper mapper;

    public ProductOfferingService(ProductOfferingRepository repository, ProductOfferingMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOfferingDto> findAll(int offset, int limit) {
        Page<ProductOffering> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductOfferingDto findById(String id) {
        ProductOffering entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOfferingDto create(ProductOfferingDto dto) {
        ProductOffering entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOffering/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public ProductOfferingDto patch(String id, ProductOfferingDto patch) {
        ProductOffering entity = repository.findById(id)
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
