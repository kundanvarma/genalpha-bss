package com.bss.policy.repository;

import com.bss.policy.entity.PolicyRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, String> {

    /** Enabled rules for a decision point, lowest priority first (first match wins). */
    List<PolicyRule> findByDomainAndEnabledTrueOrderByPriorityAsc(String domain);

    /** Enabled pricing rules, lowest priority first (all matches accumulate). */
    default List<PolicyRule> enabledPricingRules() {
        return findByDomainAndEnabledTrueOrderByPriorityAsc("pricing");
    }

    List<PolicyRule> findAllByOrderByPriorityAsc(Pageable pageable);

    /**
     * Every rule whose authored CONDITION carries this text — every domain,
     * enabled and disabled alike. An offering is attached to a rule by having
     * its id inside the condition, so this substring read is the reverse of
     * that attachment (the same way {@code teasers()} has always found them).
     * Spring Data escapes LIKE wildcards in a {@code Containing} argument, so
     * a caller cannot widen the match with a % of their own.
     */
    List<PolicyRule> findByConditionContainingOrderByPriorityAsc(String fragment);
}
