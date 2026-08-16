package com.bss.quote.repository;

import com.bss.quote.entity.QuoteConfigRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuoteConfigRuleRepository extends JpaRepository<QuoteConfigRule, String> {

    List<QuoteConfigRule> findByTenantIdOrderByCreatedAt(String tenantId);
}
