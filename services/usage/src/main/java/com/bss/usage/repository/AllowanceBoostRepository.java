package com.bss.usage.repository;

import com.bss.usage.entity.AllowanceBoost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AllowanceBoostRepository extends JpaRepository<AllowanceBoost, String> {

    List<AllowanceBoost> findByTenantIdAndOwnerPartyIdAndPeriodStart(
            String tenantId, String ownerPartyId, LocalDate periodStart);

    // Travel passes cross period boundaries (validity window, not cycle) —
    // pass lookups scan the party's boosts and filter on zone/window.
    List<AllowanceBoost> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    boolean existsByTenantIdAndProductOrderIdAndUsageSpecName(
            String tenantId, String productOrderId, String usageSpecName);
}
