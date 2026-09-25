package com.bss.revenue.repository;

import com.bss.revenue.entity.FinancialConfigChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinancialConfigChangeRepository extends JpaRepository<FinancialConfigChange, String> {

    Optional<FinancialConfigChange> findByIdAndTenantId(String id, String tenantId);

    List<FinancialConfigChange> findAllByTenantIdOrderByDraftedAtDesc(String tenantId);

    List<FinancialConfigChange> findAllByTenantIdAndStateOrderByDraftedAtDesc(String tenantId, String state);

    /** The one open change per posting key, so two people cannot climb the same ladder. */
    List<FinancialConfigChange> findAllByTenantIdAndPostingKeyAndStateIn(
            String tenantId, String postingKey, List<String> states);
}
