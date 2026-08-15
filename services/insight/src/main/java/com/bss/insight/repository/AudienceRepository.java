package com.bss.insight.repository;

import com.bss.insight.entity.Audience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AudienceRepository extends JpaRepository<Audience, String> {

    List<Audience> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<Audience> findByIdAndTenantId(String id, String tenantId);

    /** Materialized audiences, stalest first — the auto-refresh work list, bounded
     * by the caller's page size so a sweep can never fan out unboundedly. */
    List<Audience> findByTenantIdAndMaterializedAtIsNotNullOrderByMaterializedAtAsc(
            String tenantId, org.springframework.data.domain.Pageable pageable);
}
