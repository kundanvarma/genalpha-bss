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

    private final com.bss.catalog.repository.ProductOfferingRepository offerings;

    public CategoryService(CategoryRepository repository, CategoryMapper mapper, DomainEventPublisher events,
            TenantScope tenantScope, com.bss.catalog.repository.ProductOfferingRepository offerings) {
        this.offerings = offerings;
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<CategoryDto> findAll(int offset, int limit) {
        Page<Category> page = repository.findAllByTenantId(tenantScope.currentTenantId(),
                new OffsetPageRequest(offset, limit));
        java.util.Map<String, Integer> counts = offeringCounts();
        java.util.List<CategoryDto> rows = page.getContent().stream().map(entity -> {
            CategoryDto dto = mapper.toDto(entity);
            dto.setOfferingCount(counts.getOrDefault(entity.getId(), 0));
            return dto;
        }).toList();
        return new PagedResult<>(rows, page.getTotalElements());
    }

    /**
     * How many offerings sit on each shelf, so an empty one is visible on the
     * page rather than discovered by a customer. Offerings keep their category
     * refs as a JSON list on the row, so this counts over the tenant's
     * offerings once per list rather than querying per category.
     */
    private java.util.Map<String, Integer> offeringCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (com.bss.catalog.entity.ProductOffering offering
                : offerings.findByTenantId(tenantScope.currentTenantId())) {
            String json = offering.getCategoryJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            for (String id : idsIn(json)) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The ids named in a stored category ref list, without parsing the whole shape. */
    private static java.util.List<String> idsIn(String json) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.regex.Matcher m = CATEGORY_ID.matcher(json);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static final java.util.regex.Pattern CATEGORY_ID =
            java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

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
        // RETIRE, NEVER ORPHAN: offerings point at a category by id, so deleting
        // one that is still in use leaves them pointing at nothing. Refusing is
        // the simplest honest answer — the shelf can be retired instead, which
        // is what the lifecycle column is for (#155).
        int inUse = offeringCounts().getOrDefault(id, 0);
        if (inUse > 0) {
            throw new com.bss.catalog.exception.BadRequestException(
                    "this category still holds " + inUse + " offering" + (inUse == 1 ? "" : "s")
                            + " — move them first, or retire the category instead of deleting it");
        }
        CategoryDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("CategoryDeleteEvent", "category", deleted);
    }
}
