package com.bss.billing.repository;

import com.bss.billing.entity.AppliedBillingRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppliedBillingRateRepository extends JpaRepository<AppliedBillingRate, String> {

    List<AppliedBillingRate> findByTenantIdAndBillId(String tenantId, String billId);
}
