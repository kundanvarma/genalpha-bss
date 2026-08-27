package com.bss.basemigration.repository;

import com.bss.basemigration.entity.MigrationCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MigrationCustomerRepository extends JpaRepository<MigrationCustomer, String> {

    Optional<MigrationCustomer> findByIdAndTenantIdAndPlanId(String id, String tenantId, String planId);

    List<MigrationCustomer> findByTenantIdAndPlanIdOrderByCreatedAtAsc(String tenantId, String planId);

    List<MigrationCustomer> findByTenantIdAndPlanIdAndStateOrderByCreatedAtAsc(
            String tenantId, String planId, String state);

    List<MigrationCustomer> findByTenantIdAndPlanIdAndStateInOrderByCreatedAtAsc(
            String tenantId, String planId, Collection<String> states);

    boolean existsByTenantIdAndPlanIdAndProductId(String tenantId, String planId, String productId);

    long countByTenantIdAndPlanIdAndStateIn(String tenantId, String planId, Collection<String> states);

    long countByTenantIdAndPlanId(String tenantId, String planId);
}
