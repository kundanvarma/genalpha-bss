package com.bss.billing.repository;

import com.bss.billing.entity.CustomerBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CustomerBillRepository extends JpaRepository<CustomerBill, String> {

    Optional<CustomerBill> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantIdAndOwnerPartyIdAndPeriodStart(String tenantId, String ownerPartyId,
            LocalDate periodStart);
}
