package com.bss.ordering.repository;

import com.bss.ordering.entity.CreditDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditDecisionRepository extends JpaRepository<CreditDecision, String> {

    List<CreditDecision> findByTenantIdAndPartyIdOrderByDecidedAtDesc(String tenantId, String partyId);
}
