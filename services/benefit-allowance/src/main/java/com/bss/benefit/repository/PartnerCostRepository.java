package com.bss.benefit.repository;

import com.bss.benefit.entity.PartnerCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerCostRepository extends JpaRepository<PartnerCost, String> {

    boolean existsByTenantIdAndAllocationIdAndPeriod(String tenantId, String allocationId, String period);

    List<PartnerCost> findByTenantIdAndPeriodOrderByPartnerAsc(String tenantId, String period);
}
