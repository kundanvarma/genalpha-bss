package com.bss.quote.repository;

import com.bss.quote.entity.OpportunityActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OpportunityActivityRepository extends JpaRepository<OpportunityActivity, String> {

    List<OpportunityActivity> findByTenantIdAndOpportunityIdOrderByOccurredAtDesc(String tenantId, String opportunityId);
}
