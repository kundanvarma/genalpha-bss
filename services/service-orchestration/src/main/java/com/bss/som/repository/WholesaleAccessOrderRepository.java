package com.bss.som.repository;

import com.bss.som.entity.WholesaleAccessOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WholesaleAccessOrderRepository extends JpaRepository<WholesaleAccessOrder, String> {
    List<WholesaleAccessOrder> findByTenantId(String tenantId);
    List<WholesaleAccessOrder> findByTenantIdAndProductOrderId(String tenantId, String productOrderId);
    List<WholesaleAccessOrder> findByTenantIdAndState(String tenantId, String state);
}
