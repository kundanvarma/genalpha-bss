package com.bss.porting.repository;

import com.bss.porting.entity.PortingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortingOrderRepository extends JpaRepository<PortingOrder, String> {

    Optional<PortingOrder> findByIdAndTenantId(String id, String tenantId);

    List<PortingOrder> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<PortingOrder> findByTenantIdAndOwnerPartyIdAndStatus(String tenantId, String party, String status);
}
