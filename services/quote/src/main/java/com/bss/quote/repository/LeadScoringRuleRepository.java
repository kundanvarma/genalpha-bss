package com.bss.quote.repository;

import com.bss.quote.entity.LeadScoringRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadScoringRuleRepository extends JpaRepository<LeadScoringRule, String> {

    List<LeadScoringRule> findByTenantIdOrderByCreatedAt(String tenantId);
}
