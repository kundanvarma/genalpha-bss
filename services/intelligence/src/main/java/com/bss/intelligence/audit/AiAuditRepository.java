package com.bss.intelligence.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiAuditRepository extends JpaRepository<AiAudit, String> {

    List<AiAudit> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Spend this window — the budget sum, riding the tenant+createdAt index. */
    @Query("SELECT COALESCE(SUM(a.costMicros), 0) FROM AiAudit a "
            + "WHERE a.tenantId = :tenantId AND a.createdAt >= :since")
    long sumCostSince(@Param("tenantId") String tenantId, @Param("since") OffsetDateTime since);

    /** The agent-action trail: turns that DID something, newest first. */
    List<AiAudit> findTop50ByTenantIdAndActionIsNotNullOrderByCreatedAtDesc(String tenantId);
}
