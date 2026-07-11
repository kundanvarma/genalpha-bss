package com.bss.som.repository;

import com.bss.som.entity.ResourcePool;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface ResourcePoolRepository extends JpaRepository<ResourcePool, String> {

    List<ResourcePool> findByTenantId(String tenantId);

    /** Locked draw: two activations never get the same number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ResourcePool> findFirstByTenantIdAndResourceType(String tenantId, String resourceType);
}
