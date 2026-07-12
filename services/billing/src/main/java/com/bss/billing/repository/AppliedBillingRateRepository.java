package com.bss.billing.repository;

import com.bss.billing.entity.AppliedBillingRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppliedBillingRateRepository extends JpaRepository<AppliedBillingRate, String> {

    List<AppliedBillingRate> findByTenantIdAndBillId(String tenantId, String billId);

    List<AppliedBillingRate> findByTenantId(String tenantId);

    Optional<AppliedBillingRate> findByIdAndTenantId(String id, String tenantId);
}
