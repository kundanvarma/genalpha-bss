package com.bss.catalog.mapper;

import com.bss.catalog.dto.CategoryDto;
import com.bss.catalog.entity.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public CategoryDto toDto(Category entity) {
        CategoryDto dto = new CategoryDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setParentId(entity.getParentId());
        dto.setLifecycleStatus(entity.getLifecycleStatus() == null ? "Active" : entity.getLifecycleStatus());
        dto.setType("Category");
        return dto;
    }

    public Category toEntity(CategoryDto dto) {
        Category entity = new Category();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setParentId(dto.getParentId());
        entity.setLifecycleStatus(dto.getLifecycleStatus() == null ? "Active" : dto.getLifecycleStatus());
        return entity;
    }

    public void applyPatch(CategoryDto patch, Category entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getDescription() != null) {
            entity.setDescription(patch.getDescription());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        // a parent of "" is how a screen says "move this back to the top level";
        // null means the patch did not mention the parent at all
        if (patch.getParentId() != null) {
            entity.setParentId(patch.getParentId().isBlank() ? null : patch.getParentId());
        }
    }
}
