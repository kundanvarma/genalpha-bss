package com.bss.billing.repository;

import com.bss.billing.entity.CustomerBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CustomerBillRepository extends JpaRepository<CustomerBill, String> {

    java.util.List<CustomerBill> findByTenantId(String tenantId);

    java.util.List<CustomerBill> findByTenantIdAndPaymentReference(String tenantId, String paymentReference);

    Optional<CustomerBill> findByIdAndTenantId(String id, String tenantId);

    java.util.List<CustomerBill> findByTenantIdAndStateIn(String tenantId,
            java.util.Collection<String> states);

    java.util.List<CustomerBill> findByTenantIdAndOwnerPartyIdAndState(String tenantId,
            String ownerPartyId, String state);

    boolean existsByTenantIdAndOwnerPartyIdAndPeriodStart(String tenantId, String ownerPartyId,
            LocalDate periodStart);

    java.util.Optional<com.bss.billing.entity.CustomerBill>
            findFirstByTenantIdAndOwnerPartyIdOrderByPeriodEndDesc(String tenantId, String ownerPartyId);
}
