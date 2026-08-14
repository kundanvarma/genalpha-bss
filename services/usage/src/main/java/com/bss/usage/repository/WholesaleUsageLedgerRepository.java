package com.bss.usage.repository;

import com.bss.usage.entity.WholesaleUsageLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WholesaleUsageLedgerRepository extends JpaRepository<WholesaleUsageLedger, String> {
    List<WholesaleUsageLedger> findByTenantIdAndPeriodStart(String tenantId, LocalDate periodStart);
    Optional<WholesaleUsageLedger> findByTenantIdAndPeriodStartAndUsageSpecName(
            String tenantId, LocalDate periodStart, String usageSpecName);
}
