package com.bss.quote.repository;

import com.bss.quote.entity.OpportunityItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OpportunityItemRepository extends JpaRepository<OpportunityItem, String> {

    List<OpportunityItem> findByTenantIdAndOpportunityIdOrderByCreatedAt(String tenantId, String opportunityId);

    Optional<OpportunityItem> findByIdAndTenantId(String id, String tenantId);
}
