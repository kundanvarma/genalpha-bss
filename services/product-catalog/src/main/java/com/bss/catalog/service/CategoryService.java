package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.CategoryDto;
import com.bss.catalog.entity.Category;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.CategoryMapper;
import com.bss.catalog.security.TenantScope;
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
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public CategoryService(CategoryRepository repository, CategoryMapper mapper, DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<CategoryDto> findAll(int offset, int limit) {
        Page<Category> page = repository.findAllByTenantId(tenantScope.currentTenantId(),
                new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CategoryDto findById(String id) {
        Category entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public CategoryDto create(CategoryDto dto) {
        Category entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/category/" + id);
        CategoryDto created = mapper.toDto(repository.save(entity));
        events.publish("CategoryCreateEvent", "category", created);
        return created;
    }

    @Transactional
    public CategoryDto patch(String id, CategoryDto patch) {
        Category entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        mapper.applyPatch(patch, entity);
        CategoryDto updated = mapper.toDto(repository.save(entity));
        events.publish("CategoryAttributeValueChangeEvent", "category", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Category entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        CategoryDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("CategoryDeleteEvent", "category", deleted);
    }
}
