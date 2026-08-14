package com.bss.usage.repository;

import com.bss.usage.entity.WholesaleRateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WholesaleRateCardRepository extends JpaRepository<WholesaleRateCard, String> {
    List<WholesaleRateCard> findByTenantId(String tenantId);
    Optional<WholesaleRateCard> findByTenantIdAndUsageSpecName(String tenantId, String usageSpecName);
}
