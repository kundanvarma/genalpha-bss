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

    /* ---- TMF915 projections: the ledger IS the deployment record ---- */

    /** Per-scenario monitoring: calls, tokens, spend, latency — grouped. */
    @Query("SELECT a.useCase, COUNT(a), COALESCE(SUM(a.promptTokens), 0), "
            + "COALESCE(SUM(a.completionTokens), 0), COALESCE(SUM(a.costMicros), 0), "
            + "COALESCE(AVG(a.latencyMs), 0) FROM AiAudit a "
            + "WHERE a.tenantId = :tenantId AND a.useCase IS NOT NULL GROUP BY a.useCase")
    List<Object[]> contractMetrics(@Param("tenantId") String tenantId);

    /** Per-scenario outcome counts — the refusals are evidence too. */
    @Query("SELECT a.useCase, a.outcome, COUNT(a) FROM AiAudit a "
            + "WHERE a.tenantId = :tenantId AND a.useCase IS NOT NULL "
            + "GROUP BY a.useCase, a.outcome")
    List<Object[]> contractOutcomes(@Param("tenantId") String tenantId);

    /** The models that actually served: distinct provider/model/tier/scenario. */
    @Query("SELECT DISTINCT a.provider, a.model, a.tier, a.useCase FROM AiAudit a "
            + "WHERE a.tenantId = :tenantId AND a.model IS NOT NULL AND a.model <> ''")
    List<Object[]> servedModels(@Param("tenantId") String tenantId);
}
