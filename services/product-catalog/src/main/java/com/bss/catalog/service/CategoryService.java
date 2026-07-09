package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.CategoryDto;
import com.bss.catalog.entity.Category;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.CategoryMapper;
import com.bss.catalog.repository.CategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CategoryService {

    private static final String RESOURCE = "Category";

    private final CategoryRepository repository;
    private final CategoryMapper mapper;

    public CategoryService(CategoryRepository repository, CategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<CategoryDto> findAll(int offset, int limit) {
        Page<Category> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CategoryDto findById(String id) {
        Category entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public CategoryDto create(CategoryDto dto) {
        Category entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/category/" + id);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public CategoryDto patch(String id, CategoryDto patch) {
        Category entity = repository.findById(id)
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
