package com.bss.catalog.repository;

import com.bss.catalog.entity.ProductOffering;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductOfferingRepository extends JpaRepository<ProductOffering, String> {

    Optional<ProductOffering> findByIdAndTenantId(String id, String tenantId);
}
