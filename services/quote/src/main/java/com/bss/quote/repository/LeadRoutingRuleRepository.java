package com.bss.quote.repository;

import com.bss.quote.entity.LeadRoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadRoutingRuleRepository extends JpaRepository<LeadRoutingRule, String> {

    List<LeadRoutingRule> findByTenantIdOrderByMinScoreDesc(String tenantId);
}
