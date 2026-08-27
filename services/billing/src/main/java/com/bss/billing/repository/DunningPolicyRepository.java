package com.bss.billing.repository;

import com.bss.billing.entity.DunningPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DunningPolicyRepository extends JpaRepository<DunningPolicy, String> {

    Optional<DunningPolicy> findByIdAndTenantId(String id, String tenantId);

    List<DunningPolicy> findByTenantId(String tenantId);

    Optional<DunningPolicy> findFirstByTenantIdAndActiveTrueOrderByCreatedAtAsc(String tenantId);
}
