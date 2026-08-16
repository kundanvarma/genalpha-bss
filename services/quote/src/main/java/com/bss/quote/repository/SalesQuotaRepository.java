package com.bss.quote.repository;

import com.bss.quote.entity.SalesQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SalesQuotaRepository extends JpaRepository<SalesQuota, String> {
    List<SalesQuota> findByTenantIdAndQuotaPeriodOrderByOwnerName(String tenantId, String quotaPeriod);
    List<SalesQuota> findByTenantIdOrderByCreatedAt(String tenantId);
}
