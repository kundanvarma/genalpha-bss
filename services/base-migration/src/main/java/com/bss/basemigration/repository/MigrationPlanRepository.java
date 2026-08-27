package com.bss.basemigration.repository;

import com.bss.basemigration.entity.MigrationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MigrationPlanRepository extends JpaRepository<MigrationPlan, String> {

    Optional<MigrationPlan> findByIdAndTenantId(String id, String tenantId);

    List<MigrationPlan> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<MigrationPlan> findByTenantIdAndStateIn(String tenantId, Collection<String> states);
}
