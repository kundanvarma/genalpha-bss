package com.bss.usage.repository;

import com.bss.usage.entity.ProviderRateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderRateCardRepository extends JpaRepository<ProviderRateCard, String> {
    List<ProviderRateCard> findByTenantId(String tenantId);
    Optional<ProviderRateCard> findByTenantIdAndMvnoPartyIdAndUsageSpecName(
            String tenantId, String mvnoPartyId, String usageSpecName);
    Optional<ProviderRateCard> findByTenantIdAndMvnoPartyIdIsNullAndUsageSpecName(
            String tenantId, String usageSpecName);
}
