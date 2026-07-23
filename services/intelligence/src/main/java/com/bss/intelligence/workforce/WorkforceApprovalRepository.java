package com.bss.intelligence.workforce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkforceApprovalRepository extends JpaRepository<WorkforceApproval, String> {

    Optional<WorkforceApproval> findByIdAndTenantId(String id, String tenantId);

    List<WorkforceApproval> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    List<WorkforceApproval> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
