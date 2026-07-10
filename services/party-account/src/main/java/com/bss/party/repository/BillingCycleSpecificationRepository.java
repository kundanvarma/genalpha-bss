package com.bss.party.repository;

import com.bss.party.entity.BillingCycleSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingCycleSpecificationRepository extends JpaRepository<BillingCycleSpecification, String> {

    Optional<BillingCycleSpecification> findByIdAndTenantId(String id, String tenantId);
}
