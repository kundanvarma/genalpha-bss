package com.bss.catalog.repository;

import com.bss.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category, String> {

    Optional<Category> findByIdAndTenantId(String id, String tenantId);

    Page<Category> findAllByTenantId(String tenantId, Pageable pageable);
}
