package com.bss.campaign.repository;

import com.bss.campaign.entity.LearningContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningContractRepository extends JpaRepository<LearningContract, String> {

    Optional<LearningContract> findByTenantIdAndDecisionPoint(String tenantId, String decisionPoint);

    List<LearningContract> findByTenantIdOrderByDecisionPoint(String tenantId);
}
