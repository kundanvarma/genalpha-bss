package com.bss.usage.repository;

import com.bss.usage.entity.UsageAllowance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UsageAllowanceRepository extends JpaRepository<UsageAllowance, String> {

    List<UsageAllowance> findByTenantIdAndProductOfferingIdAndUsageSpecName(
            String tenantId, String offeringId, String specName);

    List<UsageAllowance> findByTenantIdAndProductOfferingId(String tenantId, String offeringId);

    List<UsageAllowance> findByTenantId(String tenantId);
}
