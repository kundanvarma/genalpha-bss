package com.bss.intelligence.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentRunbookRepository extends JpaRepository<IncidentRunbook, String> {
    Optional<IncidentRunbook> findByIdAndTenantId(String id, String tenantId);
    List<IncidentRunbook> findByTenantIdAndSignatureOrderByVersionDesc(String tenantId, String signature);
    List<IncidentRunbook> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<IncidentRunbook> findFirstByTenantIdAndSignatureAndStatusOrderByVersionDesc(
            String tenantId, String signature, String status);
    boolean existsByTenantIdAndSignatureAndStatusIn(String tenantId, String signature, List<String> statuses);
}
