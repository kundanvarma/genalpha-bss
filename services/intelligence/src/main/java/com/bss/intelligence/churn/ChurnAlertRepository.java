package com.bss.intelligence.churn;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChurnAlertRepository extends JpaRepository<ChurnAlert, String> {

    boolean existsByTenantIdAndPartyIdAndReason(String tenantId, String partyId, String reason);

    boolean existsByTenantIdAndPartyId(String tenantId, String partyId);
}
