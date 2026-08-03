package com.bss.assurance.repository;

import com.bss.assurance.entity.SlaViolation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface SlaViolationRepository extends JpaRepository<SlaViolation, String> {
    boolean existsByTenantIdAndAgreementIdAndProblemId(String tenantId, String agreementId, String problemId);
    List<SlaViolation> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<SlaViolation> findByTenantIdAndAgreementIdAndCreatedAtAfter(String tenantId,
            String agreementId, OffsetDateTime after);
}
