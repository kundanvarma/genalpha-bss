package com.bss.usage.repository;

import com.bss.usage.entity.AllowanceBoost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AllowanceBoostRepository extends JpaRepository<AllowanceBoost, String> {

    List<AllowanceBoost> findByTenantIdAndOwnerPartyIdAndPeriodStart(
            String tenantId, String ownerPartyId, LocalDate periodStart);

    boolean existsByTenantIdAndProductOrderIdAndUsageSpecName(
            String tenantId, String productOrderId, String usageSpecName);
}
