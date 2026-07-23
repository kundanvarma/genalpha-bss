package com.bss.intelligence.workforce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkforceTaskRepository extends JpaRepository<WorkforceTask, String> {

    Optional<WorkforceTask> findByIdAndTenantId(String id, String tenantId);

    List<WorkforceTask> findByTenantIdAndStatus(String tenantId, String status);

    List<WorkforceTask> findTop200ByTenantIdOrderByLastUpdateDesc(String tenantId);
}
