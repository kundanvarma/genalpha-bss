package com.bss.ordering.repository;

import com.bss.ordering.entity.ProductOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductOrderRepository extends JpaRepository<ProductOrder, String> {

    Optional<ProductOrder> findByIdAndTenantId(String id, String tenantId);
}
