package com.bss.catalog.repository;

import com.bss.catalog.entity.ProductOfferingPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductOfferingPriceRepository extends JpaRepository<ProductOfferingPrice, String> {
}
