package com.bss.billing.repository;

import com.bss.billing.entity.InstallmentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InstallmentPlanRepository extends JpaRepository<InstallmentPlan, String> {

    Optional<InstallmentPlan> findByTenantIdAndBillId(String tenantId, String billId);
}
