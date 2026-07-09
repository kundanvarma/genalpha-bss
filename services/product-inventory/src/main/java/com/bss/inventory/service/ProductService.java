package com.bss.inventory.service;

import com.bss.inventory.api.ApiConstants;
import com.bss.inventory.api.OffsetPageRequest;
import com.bss.inventory.api.PagedResult;
import com.bss.inventory.dto.ProductDto;
import com.bss.inventory.entity.Product;
import com.bss.inventory.exception.NotFoundException;
import com.bss.inventory.mapper.ProductMapper;
import com.bss.inventory.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private static final String RESOURCE = "Product";

    private final ProductRepository repository;
    private final ProductMapper mapper;

    public ProductService(ProductRepository repository, ProductMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductDto> findAll(int offset, int limit) {
        Page<Product> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductDto findById(String id) {
        Product entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductDto create(ProductDto dto) {
        Product entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/product/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public ProductDto patch(String id, ProductDto patch) {
        Product entity = repository.findById(id)
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
