package com.bss.fulfilment.repository;

import com.bss.fulfilment.entity.ShippingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShippingOrderRepository extends JpaRepository<ShippingOrder, String> {
    Optional<ShippingOrder> findByIdAndTenantId(String id, String tenantId);
    Optional<ShippingOrder> findByTenantIdAndProductOrderId(String tenantId, String productOrderId);
    List<ShippingOrder> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<ShippingOrder> findByTenantIdAndOwnerPartyIdOrderByCreatedAtDesc(String tenantId, String ownerPartyId);
}
