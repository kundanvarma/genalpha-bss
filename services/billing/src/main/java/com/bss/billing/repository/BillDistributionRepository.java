package com.bss.billing.repository;

import com.bss.billing.entity.BillDistribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface BillDistributionRepository extends JpaRepository<BillDistribution, String> {

    List<BillDistribution> findTop25ByTenantIdAndStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            String tenantId, String status, OffsetDateTime cutoff);

    List<BillDistribution> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<BillDistribution> findTop100ByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    List<BillDistribution> findByTenantIdAndBillNo(String tenantId, String billNo);
}
