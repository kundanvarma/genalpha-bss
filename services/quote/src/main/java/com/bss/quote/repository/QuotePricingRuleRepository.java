package com.bss.quote.repository;

import com.bss.quote.entity.QuotePricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuotePricingRuleRepository extends JpaRepository<QuotePricingRule, String> {
    List<QuotePricingRule> findByTenantIdOrderByCreatedAt(String tenantId);
}
