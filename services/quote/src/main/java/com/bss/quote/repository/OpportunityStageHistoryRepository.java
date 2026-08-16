package com.bss.quote.repository;

import com.bss.quote.entity.OpportunityStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OpportunityStageHistoryRepository extends JpaRepository<OpportunityStageHistory, String> {

    List<OpportunityStageHistory> findByTenantIdOrderByEnteredAt(String tenantId);

    List<OpportunityStageHistory> findByTenantIdAndOpportunityIdOrderByEnteredAt(String tenantId, String opportunityId);
}
