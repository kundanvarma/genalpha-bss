package com.bss.loyalty.repository;

import com.bss.loyalty.entity.LoyaltyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, String> {
    boolean existsByTenantIdAndCause(String tenantId, String cause);
    List<LoyaltyTransaction> findTop50ByTenantIdAndPartyIdOrderByCreatedAtDesc(String tenantId, String partyId);
}
