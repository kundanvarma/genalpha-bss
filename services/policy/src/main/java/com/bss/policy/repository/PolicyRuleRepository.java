package com.bss.policy.repository;

import com.bss.policy.entity.PolicyRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, String> {

    /** Enabled rules for a decision point, lowest priority first (first match wins). */
    List<PolicyRule> findByDomainAndEnabledTrueOrderByPriorityAsc(String domain);

    List<PolicyRule> findAllByOrderByPriorityAsc(Pageable pageable);
}
