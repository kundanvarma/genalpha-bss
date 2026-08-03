package com.bss.intelligence.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, String> {

    Optional<RiskAssessment> findByIdAndTenantId(String id, String tenantId);

    List<RiskAssessment> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
