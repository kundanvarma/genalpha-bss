package com.bss.fulfilment.repository;

import com.bss.fulfilment.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, String> {
    Optional<WorkOrder> findByIdAndTenantId(String id, String tenantId);
    Optional<WorkOrder> findByTenantIdAndProductOrderId(String tenantId, String productOrderId);
    List<WorkOrder> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<WorkOrder> findByTenantIdAndOwnerPartyIdOrderByCreatedAtDesc(String tenantId, String ownerPartyId);
}
