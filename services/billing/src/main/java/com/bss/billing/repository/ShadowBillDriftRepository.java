package com.bss.billing.repository;

import com.bss.billing.entity.ShadowBillDrift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface ShadowBillDriftRepository extends JpaRepository<ShadowBillDrift, String> {
    List<ShadowBillDrift> findTop200ByTenantIdOrderByDetectedAtDesc(String tenantId);
    boolean existsByTenantIdAndOwnerPartyIdAndOfferingIdAndBilledMonthlyAndCurrentMonthly(
            String tenantId, String ownerPartyId, String offeringId,
            BigDecimal billedMonthly, BigDecimal currentMonthly);
}
