package com.bss.usage.repository;

import com.bss.usage.entity.ProviderUsageLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProviderUsageLedgerRepository extends JpaRepository<ProviderUsageLedger, String> {
    List<ProviderUsageLedger> findByTenantIdAndPeriodStart(String tenantId, LocalDate periodStart);
    List<ProviderUsageLedger> findByTenantIdAndMvnoPartyIdAndPeriodStart(
            String tenantId, String mvnoPartyId, LocalDate periodStart);
    Optional<ProviderUsageLedger> findByTenantIdAndMvnoPartyIdAndPeriodStartAndUsageSpecName(
            String tenantId, String mvnoPartyId, LocalDate periodStart, String usageSpecName);
}
