package com.bss.intelligence.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAuditRepository extends JpaRepository<AiAudit, String> {

    List<AiAudit> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
