package com.bss.intelligence.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentTraceRepository extends JpaRepository<IncidentTrace, String> {
    Optional<IncidentTrace> findByIdAndTenantId(String id, String tenantId);
    boolean existsByTenantIdAndProcessFlowId(String tenantId, String processFlowId);
    List<IncidentTrace> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<IncidentTrace> findByTenantIdAndSignatureOrderByCreatedAtDesc(String tenantId, String signature);
}
