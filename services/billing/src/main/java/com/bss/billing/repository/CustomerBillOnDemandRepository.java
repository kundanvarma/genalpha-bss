package com.bss.billing.repository;

import com.bss.billing.entity.CustomerBillOnDemand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerBillOnDemandRepository extends JpaRepository<CustomerBillOnDemand, String> {

    Optional<CustomerBillOnDemand> findByIdAndTenantId(String id, String tenantId);

    List<CustomerBillOnDemand> findByTenantId(String tenantId);
}
