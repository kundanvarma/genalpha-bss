package com.bss.campaign.repository;

import com.bss.campaign.entity.ArbitrationDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArbitrationDecisionRepository extends JpaRepository<ArbitrationDecision, String> {

    List<ArbitrationDecision> findTop200ByTenantIdOrderByDecidedAtDesc(String tenantId);

    List<ArbitrationDecision> findByTenantIdAndPartyIdOrderByDecidedAtDesc(String tenantId, String partyId);
}
