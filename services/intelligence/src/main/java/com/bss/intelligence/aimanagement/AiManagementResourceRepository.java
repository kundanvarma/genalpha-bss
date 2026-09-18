package com.bss.intelligence.aimanagement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiManagementResourceRepository extends JpaRepository<AiManagementResource, String> {

    List<AiManagementResource> findByTenantIdAndKindOrderByCreatedAtAsc(String tenantId, String kind);

    Optional<AiManagementResource> findByTenantIdAndKindAndId(String tenantId, String kind, String id);
}
