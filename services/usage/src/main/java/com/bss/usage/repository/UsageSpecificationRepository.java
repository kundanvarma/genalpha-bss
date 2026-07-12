package com.bss.usage.repository;

import com.bss.usage.entity.UsageSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UsageSpecificationRepository extends JpaRepository<UsageSpecification, String> {
    Optional<UsageSpecification> findByIdAndTenantId(String id, String tenantId);
    List<UsageSpecification> findByTenantId(String tenantId);
}
