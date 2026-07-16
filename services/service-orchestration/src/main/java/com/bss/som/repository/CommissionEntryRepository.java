package com.bss.som.repository;

import com.bss.som.entity.CommissionEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface CommissionEntryRepository extends JpaRepository<CommissionEntry, String> {
    List<CommissionEntry> findTop200ByTenantIdAndDealerOrgIdOrderByAccruedAtDesc(String tenantId, String dealerOrgId);
    List<CommissionEntry> findByTenantIdAndServiceId(String tenantId, String serviceId);
    List<CommissionEntry> findByTenantIdAndProductOrderId(String tenantId, String productOrderId);
    List<CommissionEntry> findTop100ByTenantIdAndStatusAndHardensAtBefore(String tenantId, String status, OffsetDateTime cutoff);
}
