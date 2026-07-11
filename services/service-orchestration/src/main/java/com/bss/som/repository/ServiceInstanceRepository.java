package com.bss.som.repository;

import com.bss.som.entity.ServiceInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, String> {

    Optional<ServiceInstance> findByIdAndTenantId(String id, String tenantId);

    List<ServiceInstance> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    List<ServiceInstance> findByTenantIdAndDeliveryPath(String tenantId, String deliveryPath);
}
