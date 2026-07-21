package com.bss.intelligence.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiBudgetRepository extends JpaRepository<AiBudget, String> {

    Optional<AiBudget> findByTenantId(String tenantId);
}
