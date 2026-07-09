package com.bss.ordering.service;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import com.bss.ordering.exception.NotFoundException;
import com.bss.ordering.mapper.ProductOrderMapper;
import com.bss.ordering.repository.ProductOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProductOrderService {

    private static final String RESOURCE = "ProductOrder";

    private final ProductOrderRepository repository;
    private final ProductOrderMapper mapper;

    public ProductOrderService(ProductOrderRepository repository, ProductOrderMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProductOrderDto> findAll() {
        return repository.findAll().stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ProductOrderDto findById(String id) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOrderDto create(ProductOrderDto dto) {
        ProductOrder entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOrder/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public ProductOrderDto patch(String id, ProductOrderDto patch) {
        ProductOrder entity = repository.findById(id)
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
