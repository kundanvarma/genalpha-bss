package com.bss.catalog.repository;

import com.bss.catalog.entity.ProductOfferingPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductOfferingPriceRepository extends JpaRepository<ProductOfferingPrice, String> {

    Optional<ProductOfferingPrice> findByIdAndTenantId(String id, String tenantId);
}
