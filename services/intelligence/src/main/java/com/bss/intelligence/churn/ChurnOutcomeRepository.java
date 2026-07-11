package com.bss.intelligence.churn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChurnOutcomeRepository extends JpaRepository<ChurnOutcome, String> {

    Optional<ChurnOutcome> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<ChurnOutcome> findByTenantId(String tenantId);
}
