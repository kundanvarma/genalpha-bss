package com.bss.som.repository;

import com.bss.som.entity.ServiceOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, String> {

    Optional<ServiceOrder> findByIdAndTenantId(String id, String tenantId);

    List<ServiceOrder> findByTenantIdAndProductOrderId(String tenantId, String productOrderId);

    boolean existsByTenantIdAndProductOrderId(String tenantId, String productOrderId);
}
