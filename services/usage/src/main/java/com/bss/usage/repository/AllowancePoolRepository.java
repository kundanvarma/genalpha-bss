package com.bss.usage.repository;

import com.bss.usage.entity.AllowancePool;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface AllowancePoolRepository extends JpaRepository<AllowancePool, String> {

    Optional<AllowancePool> findByIdAndTenantId(String id, String tenantId);

    /** The reservation lock: every grant serializes on the pool row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AllowancePool> findWithLockByIdAndTenantId(String id, String tenantId);

    List<AllowancePool> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    List<AllowancePool> findByTenantId(String tenantId);
}
