package com.bss.som.repository;

import com.bss.som.entity.ServiceInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, String> {

    Optional<ServiceInstance> findByIdAndTenantId(String id, String tenantId);

    List<ServiceInstance> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    /** Boost passes past their hour: the sweep releases them (the core already has). */
    List<ServiceInstance> findTop100ByTenantIdAndSliceUntilBefore(String tenantId, java.time.OffsetDateTime before);

    List<ServiceInstance> findByTenantIdAndDeliveryPath(String tenantId, String deliveryPath);

    List<ServiceInstance> findTop100ByTenantIdAndStateAndResumeAtBefore(
            String tenantId, String state, java.time.OffsetDateTime cutoff);
}
