package com.bss.catalog.repository;

import com.bss.catalog.entity.ProductSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductSpecificationRepository extends JpaRepository<ProductSpecification, String> {

    Optional<ProductSpecification> findByIdAndTenantId(String id, String tenantId);
}
