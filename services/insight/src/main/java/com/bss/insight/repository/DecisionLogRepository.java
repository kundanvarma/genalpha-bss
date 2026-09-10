package com.bss.insight.repository;

import com.bss.insight.entity.DecisionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DecisionLogRepository extends JpaRepository<DecisionLog, String> {

    Optional<DecisionLog> findByIdAndTenantId(String id, String tenantId);

    List<DecisionLog> findByTenantIdOrderByDecidedAtDesc(String tenantId, Pageable page);

    List<DecisionLog> findByTenantIdAndDecisionPointOrderByDecidedAtDesc(String tenantId, String decisionPoint, Pageable page);

    List<DecisionLog> findByTenantIdAndSubjectIdOrderByDecidedAtDesc(String tenantId, String subjectId, Pageable page);

    List<DecisionLog> findByTenantIdAndDecisionPointAndSubjectIdOrderByDecidedAtDesc(String tenantId, String decisionPoint,
            String subjectId, Pageable page);

    /** Per decision point and policy: how many, how many with a propensity, how many with an outcome, how many fell back. */
    @Query("select d.decisionPoint, d.policy, d.policyVersion, d.source, d.autonomy, count(d), "
            + "sum(case when d.propensity is null then 0 else 1 end), "
            + "sum(case when d.outcome is null then 0 else 1 end), "
            + "sum(case when d.fallback = true then 1 else 0 end), max(d.decidedAt) "
            + "from DecisionLog d where d.tenantId = :tenant "
            + "group by d.decisionPoint, d.policy, d.policyVersion, d.source, d.autonomy order by d.decisionPoint")
    List<Object[]> summary(@Param("tenant") String tenant);
}
